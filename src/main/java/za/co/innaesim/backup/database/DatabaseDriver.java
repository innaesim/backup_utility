package za.co.innaesim.backup.database;

import java.io.IOException;
import java.nio.file.Path;

import za.co.innaesim.backup.model.AppConfig;

/** How to dump and restore one kind of database with its native tools. */
public interface DatabaseDriver {
    /**
     * Names this database, as written in config files.
     *
     * @return the type name, such as {@code mysql}
     */
    String type();
    /**
     * Gives the port used when a job doesn't set one.
     *
     * @return the database's standard port
     */
    int defaultPort();
    /**
     * Names the tool that makes backups.
     *
     * @return the executable name, without any extension
     */
    String backupTool();
    /**
     * Names the tool that restores backups.
     *
     * @return the executable name, without any extension
     */
    String restoreTool();
    /**
     * Gives the extension for this database's backup files.
     *
     * @return the extension, including its dot
     */
    String fileExtension();

    /**
     * Builds a command that writes the dump to stdout.
     *
     * @param db the database to dump
     * @param tool absolute path of the backup tool
     * @param secrets holds any password file, for the caller to delete after the command finishes
     * @return the command to run
     * @throws IOException if a password file can't be created
     */
    ProcessSpec backupCommand(AppConfig db, Path tool, SecretFiles secrets) throws IOException;

    /**
     * Builds a command that reads a dump from stdin.
     *
     * @param db the restore target
     * @param sourceDb the database the dump was taken from
     * @param tool absolute path of the restore tool
     * @param secrets holds any password file, for the caller to delete after the command finishes
     * @return the command to run
     * @throws IOException if a password file can't be created
     */
    ProcessSpec restoreCommand(AppConfig db, String sourceDb, Path tool, SecretFiles secrets) throws IOException;
}
