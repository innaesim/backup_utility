package za.co.innaesim.backup.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A remote folder of backup files. Names are plain file names, relative to the destination's folder. */
public interface RemoteStore extends AutoCloseable {
    /**
     * A file on a destination.
     *
     * @param name the plain file name
     * @param size its size in bytes
     */
    record RemoteFile(String name, long size) {}

    /**
     * Uploads a file, replacing any with the same name.
     *
     * @param file the local file
     * @param name the remote file name
     * @throws IOException if the upload fails
     */
    void upload(Path file, String name) throws IOException;

    /**
     * Lists the files directly in this folder.
     *
     * @return the files, in no particular order
     * @throws IOException if listing fails
     */
    List<RemoteFile> list() throws IOException;

    /**
     * Downloads a file.
     *
     * @param name the remote file name
     * @param target where to save it
     * @throws IOException if the download fails
     */
    void download(String name, Path target) throws IOException;

    /**
     * Deletes a file.
     *
     * @param name the remote file name
     * @throws IOException if it can't be deleted
     */
    void delete(String name) throws IOException;

    /**
     * Closes the connection.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;
}
