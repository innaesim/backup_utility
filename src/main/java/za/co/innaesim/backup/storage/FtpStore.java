package za.co.innaesim.backup.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import za.co.innaesim.backup.model.DestinationConfig;

import lombok.extern.slf4j.Slf4j;

/** FTP, or FTPS with explicit TLS when {@code tls: true}. */
@Slf4j
public final class FtpStore implements RemoteStore {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final FTPClient client;
    private final String directory;

    private FtpStore(FTPClient client, String directory) {
        this.client = client;
        this.directory = directory;
    }

    /**
     * Connects and logs in.
     *
     * @param d the destination's settings
     * @return an open connection
     * @throws IOException if connecting, logging in or creating the remote folder fails
     */
    public static FtpStore connect(DestinationConfig d) throws IOException {
        if (!d.isTls()) log.warn("Destination '{}' uses plain FTP: the password and backups travel unencrypted. Consider tls: true or sftp.", d.getName());
        FTPClient client = d.isTls() ? new FTPSClient(false) : new FTPClient();
        client.setControlEncoding("UTF-8");
        client.setConnectTimeout((int) TIMEOUT.toMillis());
        client.setDataTimeout(TIMEOUT);
        try {
            client.connect(d.getHost(), d.getPort() != null ? d.getPort() : 21);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw new IOException("Server refused the connection: " + client.getReplyString());
            if (client instanceof FTPSClient secure) {
                secure.execPBSZ(0);
                secure.execPROT("P");
            }
            if (!client.login(d.getUser(), d.getPassword())) throw new IOException("Login failed for user " + d.getUser());
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.enterLocalPassiveMode();
            FtpStore store = new FtpStore(client, d.getPath());
            store.ensureDirectory();
            return store;
        } catch (IOException e) {
            if (client.isConnected()) client.disconnect();
            throw e;
        }
    }

    private void ensureDirectory() throws IOException {
        if (directory == null || directory.equals(".")) return;
        if (client.changeWorkingDirectory(directory)) return;
        // Create one level at a time, from the root for an absolute path.
        if (directory.startsWith("/")) client.changeWorkingDirectory("/");
        for (String part : directory.split("/")) {
            if (part.isEmpty()) continue;
            if (!client.changeWorkingDirectory(part)) {
                if (!client.makeDirectory(part) || !client.changeWorkingDirectory(part)) {
                    throw new IOException("Can't create remote directory " + directory + ": " + client.getReplyString());
                }
            }
        }
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        String partial = name + ".partial";
        try (InputStream in = Files.newInputStream(file)) {
            if (!client.storeFile(partial, in)) throw new IOException("Upload failed: " + client.getReplyString());
        }
        client.deleteFile(name); // some servers won't rename over an existing file
        if (!client.rename(partial, name)) throw new IOException("Rename failed: " + client.getReplyString());
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> files = new ArrayList<>();
        for (FTPFile file : client.listFiles()) {
            if (file != null && file.isFile()) files.add(new RemoteFile(file.getName(), file.getSize()));
        }
        return files;
    }

    @Override
    public void download(String name, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            if (!client.retrieveFile(name, out)) throw new IOException("Download failed: " + client.getReplyString());
        }
    }

    @Override
    public void delete(String name) throws IOException {
        if (!client.deleteFile(name)) throw new IOException("Delete failed: " + client.getReplyString());
    }

    @Override
    public void close() throws IOException {
        if (client.isConnected()) {
            try {
                client.logout();
            } finally {
                client.disconnect();
            }
        }
    }
}
