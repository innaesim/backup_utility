package za.co.innaesim.backup.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.model.DestinationConfig;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

/** SFTP. Host keys are always verified against known_hosts; an unknown host is an error. */
public final class SftpStore implements RemoteStore {
    private final SSHClient ssh;
    private final SFTPClient sftp;
    private final String directory;

    private SftpStore(SSHClient ssh, SFTPClient sftp, String directory) {
        this.ssh = ssh;
        this.sftp = sftp;
        this.directory = directory;
    }

    /**
     * Connects and logs in.
     *
     * @param d the destination's settings
     * @return an open connection
     * @throws IOException if the host isn't known, or connecting, logging in or creating the remote folder fails
     */
    public static SftpStore connect(DestinationConfig d) throws IOException {
        SSHClient ssh = new SSHClient();
        try {
            if (d.getKnownHosts() != null) {
                ssh.loadKnownHosts(new File(d.getKnownHosts()));
            } else {
                ssh.loadKnownHosts();
            }
            ssh.setConnectTimeout(60_000);
            ssh.connect(d.getHost(), d.getPort() != null ? d.getPort() : 22);
            if (d.getKeyFile() != null) {
                KeyProvider key = d.getKeyPassphrase() != null
                        ? ssh.loadKeys(d.getKeyFile(), d.getKeyPassphrase())
                        : ssh.loadKeys(d.getKeyFile());
                ssh.authPublickey(d.getUser(), key);
            } else {
                ssh.authPassword(d.getUser(), d.getPassword());
            }
            SFTPClient sftp = ssh.newSFTPClient();
            sftp.mkdirs(d.getPath());
            return new SftpStore(ssh, sftp, d.getPath());
        } catch (IOException e) {
            ssh.close();
            throw e;
        }
    }

    private String remote(String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        String partial = remote(name + ".partial");
        sftp.put(file.toString(), partial);
        if (sftp.statExistence(remote(name)) != null) sftp.rm(remote(name));
        sftp.rename(partial, remote(name));
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> files = new ArrayList<>();
        for (RemoteResourceInfo info : sftp.ls(directory)) {
            if (info.isRegularFile()) files.add(new RemoteFile(info.getName(), info.getAttributes().getSize()));
        }
        return files;
    }

    @Override
    public void download(String name, Path target) throws IOException {
        sftp.get(remote(name), target.toString());
    }

    @Override
    public void delete(String name) throws IOException {
        sftp.rm(remote(name));
    }

    @Override
    public void close() throws IOException {
        try {
            sftp.close();
        } finally {
            ssh.close();
        }
    }
}
