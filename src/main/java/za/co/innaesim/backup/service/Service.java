package za.co.innaesim.backup.service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.database.ProcessSpec;
import za.co.innaesim.backup.database.SecretFiles;
import za.co.innaesim.backup.folder.Excludes;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.notify.Notifier;
import za.co.innaesim.backup.platform.ToolProvider;
import za.co.innaesim.backup.retention.BackupNaming;
import za.co.innaesim.backup.retention.RetentionPolicy;
import za.co.innaesim.backup.storage.RemoteStore;
import za.co.innaesim.backup.storage.StoreFactory;

import lombok.extern.slf4j.Slf4j;

/** Runs one backup: dump the database or archive the folders, upload, apply retention, notify. */
@Slf4j
public class Service {
    private static final int UPLOAD_ATTEMPTS = 3;

    private final JobConfig job;
    private final AppConfig db;
    private final Map<String, DestinationConfig> destinations;
    private final ToolProvider tools;
    private final StoreFactory stores;
    private final Notifier notifier;
    private final Scrubber scrubber;
    private final long retryDelayMillis;

    /**
     * Creates a service for one job.
     *
     * @param job the job to run
     * @param destinations all configured destinations, by name
     * @param tools supplies the database tools
     * @param stores connects to destinations
     * @param notifier told the result of each run
     */
    public Service(JobConfig job, Map<String, DestinationConfig> destinations, ToolProvider tools, StoreFactory stores, Notifier notifier) {
        this(job, destinations, tools, stores, notifier, 2_000);
    }

    Service(JobConfig job, Map<String, DestinationConfig> destinations, ToolProvider tools, StoreFactory stores, Notifier notifier, long retryDelayMillis) {
        this.job = job;
        this.db = job.getDatabase();
        this.destinations = destinations;
        this.tools = tools;
        this.stores = stores;
        this.notifier = notifier;
        this.scrubber = new Scrubber(db, destinations.values());
        this.retryDelayMillis = retryDelayMillis;
    }

    /**
     * Runs one backup. It never throws for a failed backup: the failure is in the report, and has already been sent to the notifier.
     *
     * @return what happened
     */
    public RunReport runBackup() {
        Instant start = Instant.now();
        String file = null;
        Long size = null;
        String error = null;
        Map<String, String> results = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        try (JobLock lock = JobLock.acquire(job.getName())) {
            log.info("Starting backup of job '{}'", job.getName());
            Path backup = dump(notes);
            file = backup.getFileName().toString();
            size = Files.size(backup);

            List<String> failed = upload(backup, results);
            if (failed.isEmpty()) {
                applyRetention(notes);
            } else {
                // The older local copies may be the only good ones left, so nothing gets deleted.
                error = "Upload failed for: " + String.join(", ", failed) + ". The local backup is at " + backup;
                notes.add("Retention skipped because an upload failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }

        RunReport report = new RunReport("Backup", job.getName(), describe(db), error == null,
                Duration.between(start, Instant.now()), file, size, results, notes, scrubber.clean(error));
        if (report.succeeded()) {
            log.info("Backup of job '{}' succeeded: {}", job.getName(), file);
        } else {
            log.error("Backup of job '{}' failed: {}", job.getName(), report.error());
        }
        notifier.send(report);
        return report;
    }

    static String describe(AppConfig db) {
        if (FolderArchiver.isFolder(db.getType())) return "folder " + String.join(", ", db.getPaths());
        return db.getType() + " " + db.getHost() + ":" + db.getPort() + "/" + db.getName();
    }

    private Path dump(List<String> notes) throws IOException, InterruptedException {
        boolean folder = FolderArchiver.isFolder(db.getType());
        DatabaseDriver driver = folder ? null : Drivers.forType(db.getType());
        Path tool = folder ? null : tools.require(driver.backupTool());
        Path directory = Path.of(db.getOutputDir()).toAbsolutePath();
        Files.createDirectories(directory);

        boolean compress = Boolean.TRUE.equals(db.getCompress());
        String name = new BackupNaming(db).fileName(LocalDateTime.now(), compress);
        Path target = directory.resolve(name);
        // Only a finished dump gets its real name, so a failed run can't be mistaken for a backup.
        Path partial = directory.resolve(name + ".partial");

        ProcessRunner.Result result = null;
        FolderArchiver.Result archived = null;
        try (SecretFiles secrets = new SecretFiles();
             OutputStream file = openPrivate(partial);
             OutputStream out = compress ? new GZIPOutputStream(new BufferedOutputStream(file)) : new BufferedOutputStream(file)) {
            if (folder) {
                // The output folder may sit inside a backed-up folder; earlier backups mustn't end up in this one.
                archived = FolderArchiver.write(db.getPaths().stream().map(Path::of).toList(),
                        Excludes.compile(db.getExcludes()), Set.of(directory), out);
            } else {
                ProcessSpec spec = driver.backupCommand(db, tool, secrets);
                result = ProcessRunner.run(spec, null, out);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (result != null && result.exitCode() != 0) {
            Files.deleteIfExists(partial);
            throw new IOException(driver.backupTool() + " failed with exit code " + result.exitCode() + ": " + result.stderrTail());
        }
        if (result != null && result.stdoutBytes() == 0) {
            Files.deleteIfExists(partial);
            throw new IOException(driver.backupTool() + " exited successfully but produced no data: " + result.stderrTail());
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        if (archived != null) describeArchive(archived, notes);
        return target;
    }

    private void describeArchive(FolderArchiver.Result archived, List<String> notes) {
        notes.add(archived.files() + " file(s) archived");
        if (!archived.changed().isEmpty()) {
            log.warn("{} file(s) changed while job '{}' was backing them up: {}", archived.changed().size(), job.getName(), archived.changed());
            notes.add(archived.changed().size() + " file(s) changed during the backup and may be inconsistent: " + sample(archived.changed()));
        }
        if (!archived.skipped().isEmpty()) {
            log.info("Job '{}' skipped {} special file(s): {}", job.getName(), archived.skipped().size(), archived.skipped());
            notes.add("Skipped " + archived.skipped().size() + " special file(s), such as sockets: " + sample(archived.skipped()));
        }
    }

    private static String sample(List<String> items) {
        return items.size() <= 5 ? String.join(", ", items) : String.join(", ", items.subList(0, 5)) + " and " + (items.size() - 5) + " more";
    }

    /** Dumps hold the whole database, so they're readable by their owner only where the OS allows it. */
    private static OutputStream openPrivate(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** @return names of destinations that failed; one failure doesn't stop the others */
    private List<String> upload(Path backup, Map<String, String> results) throws InterruptedException {
        List<String> failed = new ArrayList<>();
        for (String name : job.getDestinations()) {
            try {
                uploadWithRetry(destinations.get(name), backup);
                results.put(name, "uploaded");
            } catch (IOException | RuntimeException | LinkageError e) {
                String message = scrubber.clean(String.valueOf(e.getMessage()));
                log.error("Upload to '{}' failed: {}", name, message);
                results.put(name, "FAILED: " + message);
                failed.add(name);
            }
        }
        return failed;
    }

    private void uploadWithRetry(DestinationConfig destination, Path backup) throws IOException, InterruptedException {
        String name = backup.getFileName().toString();
        long expected = Files.size(backup);
        for (int attempt = 1; ; attempt++) {
            try (RemoteStore store = stores.open(destination)) {
                store.upload(backup, name);
                verifyUpload(store, name, expected);
                return;
            } catch (IOException | RuntimeException e) {
                if (attempt == UPLOAD_ATTEMPTS) throw e;
                log.warn("Upload to '{}' failed (attempt {} of {}): {}", destination.getName(), attempt, UPLOAD_ATTEMPTS, scrubber.clean(e.getMessage()));
                Thread.sleep(retryDelayMillis * attempt);
            }
        }
    }

    private static void verifyUpload(RemoteStore store, String name, long expected) throws IOException {
        for (RemoteStore.RemoteFile file : store.list()) {
            if (file.name().equals(name)) {
                if (file.size() != expected) {
                    throw new IOException("Uploaded file is " + file.size() + " bytes, expected " + expected);
                }
                return;
            }
        }
        throw new IOException("Uploaded file doesn't appear in the remote listing");
    }

    /** A retention problem is reported but doesn't fail the backup, which did succeed. */
    private void applyRetention(List<String> notes) {
        if (job.getRetention() == null) return;
        BackupNaming naming = new BackupNaming(db);
        LocalDateTime now = LocalDateTime.now();
        try {
            Path directory = Path.of(db.getOutputDir()).toAbsolutePath();
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                stream.forEach(path -> names.add(path.getFileName().toString()));
            }
            List<String> doomed = RetentionPolicy.toDelete(naming.entries(names), job.getRetention(), now);
            for (String name : doomed) Files.delete(directory.resolve(name));
            if (!doomed.isEmpty()) {
                log.info("Retention removed {} local backup(s) of job '{}': {}", doomed.size(), job.getName(), doomed);
                notes.add("Retention removed " + doomed.size() + " local backup(s)");
            }
        } catch (IOException | RuntimeException e) {
            notes.add("Local retention failed: " + scrubber.clean(e.getMessage()));
            log.warn("Local retention failed for job '{}': {}", job.getName(), scrubber.clean(e.getMessage()));
        }
        for (String name : job.getDestinations()) {
            try (RemoteStore store = stores.open(destinations.get(name))) {
                List<String> names = store.list().stream().map(RemoteStore.RemoteFile::name).collect(Collectors.toList());
                List<String> doomed = RetentionPolicy.toDelete(naming.entries(names), job.getRetention(), now);
                for (String file : doomed) store.delete(file);
                if (!doomed.isEmpty()) {
                    log.info("Retention removed {} backup(s) from '{}': {}", doomed.size(), name, doomed);
                    notes.add("Retention removed " + doomed.size() + " backup(s) from " + name);
                }
            } catch (IOException | RuntimeException e) {
                notes.add("Retention failed on " + name + ": " + scrubber.clean(e.getMessage()));
                log.warn("Retention failed on '{}': {}", name, scrubber.clean(e.getMessage()));
            }
        }
    }
}
