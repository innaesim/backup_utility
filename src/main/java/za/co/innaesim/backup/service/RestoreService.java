package za.co.innaesim.backup.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.database.SecretFiles;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.notify.Notifier;
import za.co.innaesim.backup.platform.ToolProvider;
import za.co.innaesim.backup.retention.BackupEntry;
import za.co.innaesim.backup.retention.BackupNaming;
import za.co.innaesim.backup.storage.RemoteStore;
import za.co.innaesim.backup.storage.StoreFactory;

import lombok.extern.slf4j.Slf4j;

/** Loads a backup back into a database, or extracts a folder backup, from a local file or a destination. */
@Slf4j
public class RestoreService {
    /**
     * What to restore, and where.
     *
     * @param file     a backup file name, or a path when restoring locally; null means the latest
     * @param from     destination to fetch from; null means local
     * @param targetDb  database to restore into; null means the job's own. Database jobs only
     * @param targetDir missing or empty folder to extract into. Required for folder jobs, refused for database jobs
     * @param confirm   shown the prompt, returns the typed answer; null skips confirmation (--yes)
     */
    public record Request(String file, String from, String targetDb, Path targetDir, Function<String, String> confirm) {}

    /** Gets at a backup file, which may be a temporary download, and does something with it. */
    @FunctionalInterface
    private interface SourceAction<T> {
        T apply(Path source) throws IOException, InterruptedException;
    }

    private final JobConfig job;
    private final Map<String, DestinationConfig> destinations;
    private final ToolProvider tools;
    private final StoreFactory stores;
    private final Notifier notifier;

    /**
     * Creates a service for one job.
     *
     * @param job supplies the connection settings and backup locations
     * @param destinations all configured destinations, by name
     * @param tools supplies the database tools
     * @param stores connects to destinations
     * @param notifier told the result of each restore
     */
    public RestoreService(JobConfig job, Map<String, DestinationConfig> destinations, ToolProvider tools, StoreFactory stores, Notifier notifier) {
        this.job = job;
        this.destinations = destinations;
        this.tools = tools;
        this.stores = stores;
        this.notifier = notifier;
    }

    /**
     * Lists this job's backups.
     *
     * @param from a destination to list, or null for the output directory
     * @return the backups, newest first
     * @throws IOException if the location can't be read
     */
    public List<BackupEntry> list(String from) throws IOException {
        return new BackupNaming(job.getDatabase()).entries(from == null ? localNames() : remoteNames(from));
    }

    private List<String> localNames() throws IOException {
        Path directory = Path.of(job.getDatabase().getOutputDir());
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) return names;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        return names;
    }

    private List<String> remoteNames(String from) throws IOException {
        try (RemoteStore store = stores.open(destination(from))) {
            return store.list().stream().map(RemoteStore.RemoteFile::name).collect(Collectors.toList());
        }
    }

    private DestinationConfig destination(String name) {
        DestinationConfig destination = destinations.get(name);
        if (destination == null) throw new IllegalArgumentException("No destination named '" + name + "'");
        return destination;
    }

    /**
     * Runs one restore. It never throws for a failed restore: the failure is in the report.
     *
     * @param request what to restore, and where
     * @return what happened
     */
    public RunReport restore(Request request) {
        if (FolderArchiver.isFolder(job.getDatabase().getType())) return restoreFolder(request);
        Instant start = Instant.now();
        AppConfig target = targetOf(request);
        Scrubber scrubber = new Scrubber(target, destinations.values());
        String file = request.file();
        String error = null;
        boolean confirmed = false;
        try {
            if (request.targetDir() != null) {
                throw new IllegalArgumentException("--target-dir is for folder jobs; use --target-db to restore into another database");
            }
            DatabaseDriver driver = Drivers.forType(target.getType());
            Path tool = tools.require(driver.restoreTool());
            file = resolveName(request);
            confirm(request, target, file);
            confirmed = true;
            log.info("Restoring {} into {}", file, Service.describe(target));
            load(request, file, driver, tool, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }

        RunReport report = new RunReport("Restore", job.getName(), Service.describe(target), error == null,
                Duration.between(start, Instant.now()), file, null, new LinkedHashMap<>(), List.of(), scrubber.clean(error));
        if (report.succeeded()) {
            log.info("Restore of {} succeeded", file);
        } else {
            log.error("Restore failed: {}", report.error());
        }
        // Declining the prompt changed nothing, so nobody needs alerting.
        if (confirmed) notifier.send(report);
        return report;
    }

    /** Extracts into a missing or empty folder, so nothing can be overwritten and no typed confirmation is needed. */
    private RunReport restoreFolder(Request request) {
        Instant start = Instant.now();
        Scrubber scrubber = new Scrubber(job.getDatabase(), destinations.values());
        Path target = request.targetDir() == null ? null : request.targetDir().toAbsolutePath().normalize();
        String file = request.file();
        String error = null;
        boolean started = false;
        List<String> notes = new ArrayList<>();
        try {
            if (request.targetDb() != null) throw new IllegalArgumentException("--target-db is for database jobs; use --target-dir for a folder job");
            if (target == null) throw new IllegalArgumentException("Folder jobs restore into a new or empty folder: give --target-dir");
            FolderArchiver.requireEmptyTarget(target);
            file = resolveName(request);
            started = true;
            log.info("Restoring {} into {}", file, target);
            Path into = target;
            FolderArchiver.Extracted extracted = withSource(request, file, source -> {
                try (InputStream in = open(source)) {
                    return FolderArchiver.extract(in, into);
                }
            });
            notes.add(extracted.files() + " file(s) restored");
            if (!extracted.skipped().isEmpty()) {
                notes.add("Couldn't restore " + extracted.skipped().size() + " item(s): " + String.join("; ", extracted.skipped()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        if (error != null && started) error += ". " + target + " may be partly restored";

        RunReport report = new RunReport("Restore", job.getName(), target == null ? "folder" : target.toString(), error == null,
                Duration.between(start, Instant.now()), file, null, new LinkedHashMap<>(), notes, scrubber.clean(error));
        if (report.succeeded()) {
            log.info("Restore of {} into {} succeeded", file, target);
        } else {
            log.error("Restore failed: {}", report.error());
        }
        // Refused before anything was written, so nobody needs alerting.
        if (started) notifier.send(report);
        return report;
    }

    private AppConfig targetOf(Request request) {
        AppConfig source = job.getDatabase();
        AppConfig target = new AppConfig();
        target.setType(source.getType());
        target.setHost(source.getHost());
        target.setPort(source.getPort());
        target.setUser(source.getUser());
        target.setPassword(source.getPassword());
        target.setAuthDb(source.getAuthDb());
        target.setName(request.targetDb() != null ? request.targetDb() : source.getName());
        return target;
    }

    private String resolveName(Request request) throws IOException {
        if (request.file() != null) {
            if (request.from() != null && (request.file().contains("/") || request.file().contains("\\"))) {
                throw new IllegalArgumentException("With --from, --file must be a plain file name as shown by 'list'");
            }
            return request.file();
        }
        List<BackupEntry> entries = list(request.from());
        if (entries.isEmpty()) {
            throw new IOException("No backups of job '" + job.getName() + "' found in "
                    + (request.from() != null ? "destination " + request.from() : job.getDatabase().getOutputDir()));
        }
        return entries.get(0).name();
    }

    /** Restoring overwrites live data, so the user must type the database name, not just press y. */
    private static void confirm(Request request, AppConfig target, String file) {
        if (request.confirm() == null) return;
        String prompt = "About to restore " + file + " into " + Service.describe(target)
                + ".\nThis can overwrite existing data. Type the database name '" + target.getName() + "' to continue:";
        String answer = request.confirm().apply(prompt);
        if (!target.getName().equals(answer)) throw new IllegalStateException("Restore cancelled: the database name wasn't confirmed");
    }

    private void load(Request request, String file, DatabaseDriver driver, Path tool, AppConfig target) throws IOException, InterruptedException {
        withSource(request, file, source -> {
            runRestore(source, driver, tool, target);
            return null;
        });
    }

    private <T> T withSource(Request request, String file, SourceAction<T> action) throws IOException, InterruptedException {
        Path temporaryDir = null;
        try {
            Path source;
            if (request.from() != null) {
                temporaryDir = Files.createTempDirectory("backup-utility-restore");
                source = temporaryDir.resolve("download");
                try (RemoteStore store = stores.open(destination(request.from()))) {
                    log.info("Downloading {} from '{}'", file, request.from());
                    store.download(file, source);
                }
            } else {
                source = localFile(file);
            }
            return action.apply(source);
        } finally {
            if (temporaryDir != null) {
                Files.deleteIfExists(temporaryDir.resolve("download"));
                Files.deleteIfExists(temporaryDir);
            }
        }
    }

    private Path localFile(String file) throws IOException {
        Path inOutputDir = Path.of(job.getDatabase().getOutputDir() == null ? "." : job.getDatabase().getOutputDir()).resolve(file);
        if (Files.isRegularFile(inOutputDir)) return inOutputDir;
        Path direct = Path.of(file);
        if (Files.isRegularFile(direct)) return direct;
        throw new IOException("Backup file not found: " + file);
    }

    private void runRestore(Path source, DatabaseDriver driver, Path tool, AppConfig target) throws IOException, InterruptedException {
        ProcessRunner.Result result;
        try (SecretFiles secrets = new SecretFiles(); InputStream in = open(source)) {
            result = ProcessRunner.run(driver.restoreCommand(target, job.getDatabase().getName(), tool, secrets), in, null);
        }
        if (result.exitCode() != 0) {
            throw new IOException(driver.restoreTool() + " failed with exit code " + result.exitCode() + ": " + result.stderrTail());
        }
    }

    /** Detects gzip from the content, because a user-supplied file might be named anything. */
    private static InputStream open(Path source) throws IOException {
        BufferedInputStream in = new BufferedInputStream(Files.newInputStream(source));
        try {
            in.mark(2);
            int first = in.read();
            int second = in.read();
            in.reset();
            boolean gzip = first == 0x1f && second == 0x8b;
            return gzip ? new GZIPInputStream(in) : in;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }
}
