# Java Database Backup Utility

Back up and restore **PostgreSQL**, **MySQL** and **MongoDB** databases, and **folders**, on demand or on a schedule. Backups can be pushed to FTP, SFTP, S3-compatible storage or Azure Blob Storage, cleaned up by a retention policy, and reported to Slack.

## Requirements

- **Java 17 or newer**
- The client tools for your database. `doctor` tells you what's missing and offers to install it. Folder backups need no extra tools.

| Database | Backup | Restore |
|---|---|---|
| PostgreSQL | `pg_dump` | `psql` |
| MySQL | `mysqldump` | `mysql` |
| MongoDB | `mongodump` | `mongorestore` |

## Quick start

From a clone of this repository:

```bash
./install.sh                                              # macOS and Linux
powershell -ExecutionPolicy Bypass -File .\install.ps1    # Windows
```

The installer does the bare minimum for the current user:

- Checks for Java 17 or newer and offers to install it. Building also needs Maven, which it offers to install on macOS and Linux. Windows has to install Maven by hand, or skip the build with `-Jar FILE`.
- Builds the jar and installs it with a `backup-utility` launcher (`~/.local/share/backup-utility` and `~/.local/bin`, or `%LOCALAPPDATA%\backup-utility` on Windows).
- Creates `~/.backup-utility/` with a private, empty `config.yaml` and `env`, plus `config.example.yaml` for reference.

It doesn't install database tools or start a service; those are your next steps. Re-run it to upgrade: existing config and env files are never overwritten. Use `--uninstall` (`-Uninstall` on Windows) to remove the program, which keeps your config and backups. `--help` lists the other options.

```bash
backup-utility doctor --install       # install the tools for your database
# add your jobs to ~/.backup-utility/config.yaml, using config.example.yaml as a guide
backup-utility backup --job orders
```

To run it without installing, use `mvn package`, then `java -jar target/backup-utility.jar`. With no arguments it prompts for each setting and runs one backup.

## Commands

| Command | What it does |
|---|---|
| `backup --job NAME` | Run one job from the config file. |
| `backup --all` | Run every job. One failure doesn't stop the others. |
| `backup --type mysql --db shop --ask-password` | Run a one-off backup without a config file. See `backup --help`. |
| `backup --type folder --path ~/Documents` | Run a one-off folder backup. |
| `restore --job NAME --latest` | Restore the newest backup. |
| `restore --job NAME --file FILE` | Restore a specific backup. |
| `restore --job NAME --latest --target-dir DIR` | Restore a folder backup into a new or empty folder. |
| `list --job NAME [--from DESTINATION]` | List backups, newest first. |
| `schedule add\|list\|remove` | Manage schedules from the command line. |
| `daemon` | Run scheduled backups until stopped. |
| `service enable\|disable\|status` | Start the daemon automatically and restart it if it dies. |
| `doctor [--install]` | Show the OS and tool status, and offer to install what's missing. |

Every command has `--help`. Commands exit non-zero on failure, so they work in scripts. There's deliberately no `--password` option, because it would show up in `ps` and your shell history. Use `--password-env VAR`, `--ask-password` or the config file.

## Configuration

The default location is `~/.backup-utility/config.yaml`; `--config` overrides it. See [`config.example.yaml`](config.example.yaml) for every setting.

```yaml
destinations:
  offsite: { type: s3, bucket: my-backups, region: eu-west-1, prefix: db/ }
notifications:
  slack: { webhook_url: "${SLACK_WEBHOOK}" }
jobs:
  orders:
    type: postgresql
    host: db.internal
    name: orders
    user: backup
    password: "${ORDERS_PW}"
    output_dir: /var/backups/orders
    compress: true
    schedule: "0 2 * * *"
    destinations: [offsite]
    retention: { days: 30, keep_last: 5 }
```

- **Secrets:** `${VAR}` is replaced from the environment, then from the file `~/.backup-utility/env` (`KEY=value` lines). Keep secrets there and `chmod 600` it.
- **Validation:** the whole config is checked on load and every problem is reported at once, including misspelled settings.
- **Relative paths** such as `output_dir: backups` are relative to the config file.
- **File names** come from `file_pattern`, default `{db}_{date}{ext}`, and must contain `{date}`. Compressed backups get `.gz` added.

## Folder backups

A job with `type: folder` archives one or more folders into a single tar file, `.tar.gz` with `compress: true`. It uses the same scheduling, destinations, retention and Slack reports as database jobs.

```yaml
jobs:
  documents:
    type: folder
    paths: [~/Documents, /srv/app/uploads]
    exclude: ["node_modules", "*.tmp", "cache/**"]
    output_dir: /var/backups/documents
    compress: true
    schedule: "0 3 * * *"
    retention: { keep_last: 7 }
```

- **Layout:** each folder is stored under its own name, so the archive holds `Documents/...` and `uploads/...`. Two folders with the same name are rejected; back them up in separate jobs.
- **Excludes:** a pattern without a `/` matches that name at any depth, such as `node_modules` or `*.tmp`. A pattern with a `/` matches the path relative to each folder, such as `cache/**`. Excluded folders aren't entered.
- **File names:** `{db}` in `file_pattern` is the job name, or `name:` if you set one.
- **What's kept:** file contents, modification times and, on Linux and macOS, permissions. Symlinks are stored as links and never followed. Sockets and other special files are skipped.
- **Errors:** a file that can't be read fails the backup. A file that changes while it's being read is still stored, and the report lists it.
- **The output folder is skipped** if it's inside a folder being backed up, so backups never contain earlier backups.

To restore, extract into a folder that doesn't exist yet or is empty, then copy back what you need. Nothing is ever overwritten, so there's no confirmation prompt.

```bash
backup-utility restore --job documents --latest --target-dir ~/restored
backup-utility restore --job documents --latest --from offsite --target-dir ~/restored
```

The archives are ordinary tar files, so `tar xzf` works too.

## Scheduling

Schedules are 5-field cron expressions, and they run while the daemon is running.

```bash
# Add a schedule to a job in your config file
backup-utility schedule add --job orders --cron "0 2 * * *"

# Or define a new job entirely from the command line
backup-utility schedule add --name shop --cron "30 1 * * *" \
    --type mysql --db shop --user backup --password-env SHOP_PW \
    --output /var/backups/shop --compress --keep-last 7

backup-utility daemon
```

`schedule add` writes to `~/.backup-utility/schedules.d/`, so your hand-written config and its comments are never changed. It stores only the name of a password variable, never the password.

The daemon re-reads its configuration every minute, so changes need no restart. If a change makes the config invalid, the error is logged and the last good config stays in use. If a job is still running when its next time arrives, that run is skipped. Runs missed while the machine was off aren't caught up.

## Restarting after a reboot

```bash
backup-utility service enable           # no root needed
sudo backup-utility service enable --system --config /path/to/config.yaml
backup-utility service status
backup-utility service disable
```

| OS | `service enable` | `service enable --system` |
|---|---|---|
| macOS | LaunchAgent: starts **when you log in** | LaunchDaemon: starts at boot |
| Linux | systemd user unit plus lingering: starts at boot | system unit: starts at boot |
| Windows | Scheduled task: starts **when you log in** | Scheduled task: starts at boot (run from an Administrator prompt) |

All of them restart the daemon if it crashes.

- **Services don't see your shell's environment.** Put any `${VAR}` values in the `env` file beside the config file.
- **State lives beside the config file.** The service keeps its `schedules.d`, `env` and `logs` in the config file's directory, so run `schedule add` with the same home, or use the default location.
- **Restart it after upgrading.** Run `service disable` and then `service enable` to pick up a new version. On macOS and Linux, the installer can replace the jar while the daemon runs, and the daemon keeps using the old version until restarted. On Windows, disable the service before upgrading, because a running jar can't be replaced.
- **After a Java upgrade,** if the installer found Java in a versioned directory, re-run the installer and then disable and enable the service.

## Destinations

| Type | Settings | Notes |
|---|---|---|
| `ftp` | `host`, `port`, `user`, `password`, `path`, `tls` | `tls: true` uses FTPS. Without it, the password and backups travel unencrypted. |
| `sftp` | `host`, `port`, `user`, `key_file`, `key_passphrase`, `password`, `path`, `known_hosts` | The host must be in `known_hosts` (default `~/.ssh/known_hosts`). Unknown hosts are refused. |
| `s3` | `bucket`, `region`, `prefix`, `endpoint`, `access_key`, `secret_key` | Set `endpoint` for MinIO, R2, B2, Spaces and others. Without keys, the standard AWS credential chain is used. |
| `azure` | `container`, `connection_string`, `prefix` | The container must already exist. |

Each upload gets up to 3 attempts and its size is checked afterwards. If one destination fails, the others still run, the local copy is kept, and the job is reported as failed.

## Retention

`retention: { days: 30, keep_last: 5 }` keeps a backup if it is within 30 days **or** among the newest 5. Set either or both. It applies to the output directory and every destination.

To protect good backups:

- It only runs after a successful backup with all uploads done, so repeated failures can't delete what you have.
- The newest backup is never deleted.
- It only touches files matching that job's name pattern, and reads the age from the file name. Other files are left alone.
- Two jobs that would produce the same file names in the same place are rejected as a config error, because they'd delete each other's backups.

## Slack

Add an [incoming webhook](https://api.slack.com/messaging/webhooks) under `notifications.slack`. Backups and restores report their result, size and destination status, and failures include the tool's error output. Known passwords are removed from messages. `notify_on: [failure]` limits it to failures. A Slack outage never fails a backup.

## Restore

```bash
backup-utility restore --job orders --latest
backup-utility restore --job orders --latest --from offsite
backup-utility restore --job orders --file orders_20260115_020000.sql.gz --target-db orders_check
```

Restore shows the target and asks you to type the database name; `--yes` skips this for scripts. For folder jobs, see [Folder backups](#folder-backups).

- **Restoring into a fresh database is safest.** MySQL dumps drop and recreate their tables. PostgreSQL dumps don't drop anything, so restoring over existing tables fails with an error. `mongorestore` skips documents whose `_id` already exists.
- **The target must exist** for PostgreSQL and MySQL. MongoDB creates it.
- **A failed restore can leave a partial result,** because the tools apply a dump statement by statement.

Test your restores regularly. A backup you've never restored is only a hope.

## Security notes

- Passwords never appear in command lines. PostgreSQL uses `PGPASSWORD`; MySQL and MongoDB use owner-only temporary files that are deleted afterwards.
- Backups contain your whole database or folder. On Linux and macOS they're created readable by their owner only. On Windows they inherit the folder's permissions, so choose the output folder accordingly.
- A warning is logged if your config or env file is readable by other users.

Logs are in `~/.backup-utility/logs/`.

## License

This project is licensed under the MIT License

---

© 2025 Duncan Johanne Kachasu. All rights reserved.
