#!/bin/sh
# Installs backup-utility for the current user on macOS or Linux. Safe to re-run: it upgrades the
# jar and launcher, and never touches an existing config, env file or backups.
#
# It doesn't install database tools (run "backup-utility doctor --install" for the ones you need)
# and doesn't start a service (run "backup-utility service enable" when you're ready).
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/backup-utility"
BIN_DIR="$HOME/.local/bin"
CONFIG_DIR="${BACKUP_UTILITY_HOME:-$HOME/.backup-utility}"
MIN_JAVA=17

ASSUME_YES=0
UNINSTALL=0
PREBUILT_JAR=""

usage() {
    cat <<EOF
Usage: ./install.sh [options]

  --jar FILE    Install this jar instead of building one with Maven
  --yes         Answer yes to every prompt, including installing Java or Maven
  --uninstall   Remove the jar and launcher; keeps your config and backups
  --help        Show this help

Installs to $DATA_DIR and $BIN_DIR, with config in $CONFIG_DIR.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --jar)
            [ $# -ge 2 ] || { echo "Error: --jar needs a file" >&2; exit 2; }
            PREBUILT_JAR=$2
            shift 2
            ;;
        --yes) ASSUME_YES=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Error: unknown option $1" >&2; usage >&2; exit 2 ;;
    esac
done

say() { printf '%s\n' "$*"; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

# End of input counts as no, so nothing gets installed unattended without --yes.
confirm() {
    [ "$ASSUME_YES" -eq 1 ] && return 0
    printf '%s [y/N]: ' "$1"
    read -r answer || answer=""
    case "$answer" in y|Y|yes|YES|Yes) return 0 ;; *) return 1 ;; esac
}

sudo_prefix() {
    if [ "$(id -u)" -eq 0 ]; then echo ""; else echo "sudo "; fi
}

# Prints the first enabled service definition, if any.
find_service() {
    for file in \
        "$HOME/Library/LaunchAgents/za.co.innaesim.backup-utility.plist" \
        "/Library/LaunchDaemons/za.co.innaesim.backup-utility.plist" \
        "$HOME/Library/LaunchAgents/com.db.backup-utility.plist" \
        "/Library/LaunchDaemons/com.db.backup-utility.plist" \
        "$HOME/.config/systemd/user/backup-utility.service" \
        "/etc/systemd/system/backup-utility.service"; do
        if [ -e "$file" ]; then echo "$file"; return 0; fi
    done
    return 0
}

if [ "$UNINSTALL" -eq 1 ]; then
    service=$(find_service)
    if [ -n "$service" ]; then
        fail "the service is still enabled ($service). Run 'backup-utility service disable' first, with sudo and --system if it's system-level."
    fi
    rm -f "$BIN_DIR/backup-utility" "$DATA_DIR/backup-utility.jar"
    rmdir "$DATA_DIR" 2>/dev/null || true
    say "Removed the launcher and jar."
    say "Your config and logs are still in $CONFIG_DIR, and your backups are wherever your jobs put them."
    exit 0
fi

case "$(uname -s)" in
    Darwin) OS=macos ;;
    Linux) OS=linux ;;
    *) fail "this script supports macOS and Linux. On Windows, use install.ps1." ;;
esac

if [ "$OS" = macos ]; then
    if command -v brew >/dev/null 2>&1; then PM=brew; else PM=none; fi
elif command -v apt-get >/dev/null 2>&1; then PM=apt
elif command -v dnf >/dev/null 2>&1; then PM=dnf
elif command -v yum >/dev/null 2>&1; then PM=yum
else PM=none
fi

java_major() {
    # Prints the major version, or nothing for a stub that can't run, like macOS's /usr/bin/java with no JDK.
    version=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1) || true
    printf '%s' "$version"
}

find_java() {
    for candidate in \
        "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
        "$(command -v java 2>/dev/null || true)" \
        /opt/homebrew/opt/openjdk@21/bin/java \
        /opt/homebrew/opt/openjdk/bin/java \
        /usr/local/opt/openjdk@21/bin/java \
        /usr/local/opt/openjdk/bin/java; do
        [ -n "$candidate" ] && [ -x "$candidate" ] || continue
        major=$(java_major "$candidate")
        if [ -n "$major" ] && [ "$major" -ge "$MIN_JAVA" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 0
}

java_install_command() {
    case "$PM" in
        brew) echo "brew install openjdk@21" ;;
        apt)
            # Debian and Ubuntu releases each ship different versions, so ask which exists.
            if apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then package=openjdk-21-jdk-headless
            else package=openjdk-17-jdk-headless; fi
            echo "$(sudo_prefix)apt-get install -y $package"
            ;;
        dnf|yum) echo "$(sudo_prefix)$PM install -y java-21-openjdk-devel" ;;
        none) echo "" ;;
    esac
}

maven_install_command() {
    case "$PM" in
        brew) echo "brew install maven" ;;
        apt) echo "$(sudo_prefix)apt-get install -y maven" ;;
        dnf|yum) echo "$(sudo_prefix)$PM install -y maven" ;;
        none) echo "" ;;
    esac
}

# Offers to run an install command. Word-splitting the command is intended; it holds no user input.
offer_install() {
    what=$1
    command_line=$2
    if [ -z "$command_line" ]; then
        fail "$what is required and no supported package manager was found. Install $what, then re-run this script."
    fi
    say "$what is required. It can be installed with:"
    say "  $command_line"
    confirm "Run this command now?" || fail "$what is required. To install it, run: $command_line"
    # shellcheck disable=SC2086
    $command_line
}

say "Installing backup-utility for $OS"

JAVA=$(find_java)
if [ -z "$JAVA" ]; then
    offer_install "Java $MIN_JAVA or newer" "$(java_install_command)"
    JAVA=$(find_java)
    [ -n "$JAVA" ] || fail "Java was installed but can't be found. Add it to PATH, then re-run this script."
fi
say "Java: $JAVA ($(java_major "$JAVA"))"

if [ -n "$PREBUILT_JAR" ]; then
    [ -f "$PREBUILT_JAR" ] || fail "jar not found: $PREBUILT_JAR"
    SOURCE_JAR=$PREBUILT_JAR
else
    [ -f "$SCRIPT_DIR/pom.xml" ] || fail "no pom.xml beside this script. Run it from a clone of the repository, or pass --jar FILE."
    if ! command -v mvn >/dev/null 2>&1; then
        offer_install "Maven" "$(maven_install_command)"
        command -v mvn >/dev/null 2>&1 || fail "Maven was installed but can't be found. Add it to PATH, then re-run this script."
    fi
    say "Building the jar (this can take a minute)..."
    (cd "$SCRIPT_DIR" && mvn -q -DskipTests package) || fail "the build failed. See the output above."
    SOURCE_JAR="$SCRIPT_DIR/target/backup-utility.jar"
    [ -f "$SOURCE_JAR" ] || fail "the build finished but $SOURCE_JAR is missing."
fi

mkdir -p "$DATA_DIR" "$BIN_DIR"

# Replaced with a rename, so a running daemon keeps its open copy rather than breaking mid-backup.
cp "$SOURCE_JAR" "$DATA_DIR/backup-utility.jar.new"
mv -f "$DATA_DIR/backup-utility.jar.new" "$DATA_DIR/backup-utility.jar"

# backup.utility.java is what 'service enable' records. Without it, the service records the resolved
# path, such as a versioned Homebrew directory, and stops starting after a Java upgrade.
launcher_tmp="$BIN_DIR/backup-utility.new"
cat > "$launcher_tmp" <<EOF
#!/bin/sh
exec "$JAVA" -Dbackup.utility.java="$JAVA" -jar "$DATA_DIR/backup-utility.jar" "\$@"
EOF
chmod 755 "$launcher_tmp"
mv -f "$launcher_tmp" "$BIN_DIR/backup-utility"
say "Installed: $BIN_DIR/backup-utility"

mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"

# Created empty rather than from the example: its placeholder jobs and unset variables would make
# every command fail. An existing file is never overwritten.
if [ ! -e "$CONFIG_DIR/config.yaml" ]; then
    (umask 077; cat > "$CONFIG_DIR/config.yaml" <<EOF
# backup-utility configuration. See config.example.yaml beside this file for every setting.
EOF
    )
    say "Created: $CONFIG_DIR/config.yaml"
else
    say "Kept existing: $CONFIG_DIR/config.yaml"
fi
if [ ! -e "$CONFIG_DIR/env" ]; then
    (umask 077; cat > "$CONFIG_DIR/env" <<EOF
# Secrets for \${VAR} in config.yaml, one KEY=value per line. Keep this file private.
EOF
    )
    say "Created: $CONFIG_DIR/env"
else
    say "Kept existing: $CONFIG_DIR/env"
fi
if [ -f "$SCRIPT_DIR/config.example.yaml" ]; then
    cp "$SCRIPT_DIR/config.example.yaml" "$CONFIG_DIR/config.example.yaml"
fi

case ":$PATH:" in
    *":$BIN_DIR:"*) ON_PATH=1 ;;
    *) ON_PATH=0 ;;
esac
if [ "$ON_PATH" -eq 0 ]; then
    case "${SHELL:-}" in
        */zsh) RC="$HOME/.zshrc" ;;
        */bash) if [ "$OS" = macos ]; then RC="$HOME/.bash_profile"; else RC="$HOME/.bashrc"; fi ;;
        *) RC="$HOME/.profile" ;;
    esac
    # Written literally, so $HOME and $PATH are expanded by the shell at login.
    PATH_LINE='export PATH="$HOME/.local/bin:$PATH"'
    say ""
    say "$BIN_DIR isn't on your PATH."
    if [ -f "$RC" ] && grep -qF "$PATH_LINE" "$RC"; then
        say "It's already set in $RC, so open a new terminal."
    elif confirm "Add it to $RC?"; then
        printf '\n%s\n' "$PATH_LINE" >> "$RC"
        say "Added. Open a new terminal for it to take effect."
    else
        say "To add it yourself, put this in your shell profile:"
        say "  $PATH_LINE"
    fi
fi

service=$(find_service)
if [ -n "$service" ]; then
    say ""
    say "A service is enabled ($service) and is still running the previous version."
    say "To switch to this one: backup-utility service disable && backup-utility service enable"
    say "Add sudo and --system to both if it's system-level."
fi

say ""
"$BIN_DIR/backup-utility" doctor 2>/dev/null || fail "the installed program didn't run."
say ""
say "Done. Next steps:"
say "  1. Install the tools for your database:  backup-utility doctor --install"
say "  2. Add your jobs to:                     $CONFIG_DIR/config.yaml"
say "  3. Run a backup:                         backup-utility backup --job NAME"
say "  4. Start scheduled backups at startup:   backup-utility service enable"
