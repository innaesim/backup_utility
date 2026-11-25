package za.co.innaesim.backup.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

import za.co.innaesim.backup.model.DestinationConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Runs each store against a real local server: FTP, SSH and an S3-speaking HTTP stub. */
class RemoteStoresTest {
    private static final byte[] DATA = "backup contents".getBytes();

    @TempDir
    Path dir;

    private void exercise(RemoteStore store) throws IOException {
        Path source = dir.resolve("source.sql");
        Files.write(source, DATA);
        store.upload(source, "shop_1.sql");

        List<RemoteStore.RemoteFile> files = store.list();
        assertEquals(List.of(new RemoteStore.RemoteFile("shop_1.sql", DATA.length)), files, "no .partial left behind");

        Path downloaded = dir.resolve("downloaded");
        store.download("shop_1.sql", downloaded);
        assertArrayEquals(DATA, Files.readAllBytes(downloaded));

        store.upload(source, "shop_1.sql"); // re-upload, as a retry would
        assertEquals(1, store.list().size());

        store.delete("shop_1.sql");
        assertTrue(store.list().isEmpty());
    }

    @Test
    void ftp() throws IOException {
        FakeFtpServer server = new FakeFtpServer();
        server.setServerControlPort(0);
        server.addUserAccount(new UserAccount("bob", "pw", "/"));
        UnixFakeFileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry("/"));
        server.setFileSystem(fileSystem);
        server.start();
        try {
            DestinationConfig d = new DestinationConfig();
            d.setName("ftp");
            d.setType("ftp");
            d.setHost("127.0.0.1");
            d.setPort(server.getServerControlPort());
            d.setUser("bob");
            d.setPassword("pw");
            d.setPath("/deep/backups");
            try (RemoteStore store = FtpStore.connect(d)) {
                exercise(store);
            }
            assertTrue(fileSystem.exists("/deep/backups"), "remote path is created");

            d.setPassword("wrong");
            assertThrows(IOException.class, () -> FtpStore.connect(d));
        } finally {
            server.stop();
        }
    }

    private SshServer startSsh(Path root) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey")));
        server.setPasswordAuthenticator((user, password, session) -> "bob".equals(user) && "pw".equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        return server;
    }

    private DestinationConfig sftpDestination(SshServer server, Path knownHosts) {
        DestinationConfig d = new DestinationConfig();
        d.setName("sftp");
        d.setType("sftp");
        d.setHost("127.0.0.1");
        d.setPort(server.getPort());
        d.setUser("bob");
        d.setPassword("pw");
        d.setPath("/deep/backups");
        d.setKnownHosts(knownHosts.toString());
        return d;
    }

    @Test
    void sftp() throws Exception {
        Path root = Files.createDirectory(dir.resolve("root"));
        SshServer server = startSsh(root);
        try {
            KeyPairProvider keys = server.getKeyPairProvider();
            KeyPair key = keys.loadKeys(null).iterator().next();
            Path knownHosts = dir.resolve("known_hosts");
            Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " " + PublicKeyEntry.toString(key.getPublic()) + "\n");

            try (RemoteStore store = SftpStore.connect(sftpDestination(server, knownHosts))) {
                exercise(store);
            }
            assertTrue(Files.isDirectory(root.resolve("deep/backups")), "remote path is created");
        } finally {
            server.stop();
        }
    }

    @Test
    void sftpRefusesAnUnknownHost() throws Exception {
        SshServer server = startSsh(Files.createDirectory(dir.resolve("root")));
        try {
            Path empty = Files.writeString(dir.resolve("empty_known_hosts"), "");
            assertThrows(IOException.class, () -> SftpStore.connect(sftpDestination(server, empty)));
        } finally {
            server.stop();
        }
    }

    /** Just enough of the S3 protocol: path-style PUT, GET, DELETE and ListObjectsV2. */
    private HttpServer startFakeS3(Map<String, byte[]> objects) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleS3(exchange, objects));
        server.start();
        return server;
    }

    private static void handleS3(HttpExchange exchange, Map<String, byte[]> objects) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String key = path.replaceFirst("^/bkt/?", "");
        byte[] body = new byte[0];
        int status = 200;
        String etag = null;
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                byte[] uploaded = decodeChunked(exchange.getRequestBody().readAllBytes(), exchange);
                objects.put(key, uploaded);
                etag = md5(uploaded); // the SDK checks this against what it sent
            }
            case "DELETE" -> {
                objects.remove(key);
                status = 204;
            }
            case "GET" -> {
                if (query != null && query.contains("list-type=2")) {
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><ListBucketResult><IsTruncated>false</IsTruncated>");
                    objects.forEach((k, v) -> xml.append("<Contents><Key>").append(k).append("</Key><Size>").append(v.length).append("</Size></Contents>"));
                    body = xml.append("</ListBucketResult>").toString().getBytes();
                } else if (objects.containsKey(key)) {
                    body = objects.get(key);
                } else {
                    status = 404;
                }
            }
            default -> status = 405;
        }
        exchange.getRequestBody().close();
        if (etag != null) exchange.getResponseHeaders().add("ETag", "\"" + etag + "\"");
        exchange.sendResponseHeaders(status, status == 204 || body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String md5(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SDK signs plain-HTTP uploads as aws-chunked: "len;chunk-signature=...\r\ndata\r\n...". */
    private static byte[] decodeChunked(byte[] raw, HttpExchange exchange) {
        String sha = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (sha == null || !sha.startsWith("STREAMING")) return raw;
        String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        int headerEnd = text.indexOf("\r\n");
        int length = Integer.parseInt(text.substring(0, text.indexOf(';')), 16);
        return text.substring(headerEnd + 2, headerEnd + 2 + length).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    void s3CompatibleEndpoint() throws IOException {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        HttpServer server = startFakeS3(objects);
        try {
            DestinationConfig d = new DestinationConfig();
            d.setName("s3");
            d.setType("s3");
            d.setBucket("bkt");
            d.setRegion("us-east-1");
            d.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            d.setAccessKey("key");
            d.setSecretKey("secret");
            d.setPrefix("db/");
            try (RemoteStore store = S3Store.connect(d)) {
                Path source = dir.resolve("source.sql");
                Files.write(source, DATA);
                store.upload(source, "shop_1.sql");
                assertArrayEquals(DATA, objects.get("db/shop_1.sql"), "object key includes the prefix");
                objects.put("db/nested/other.sql", new byte[1]);
                assertEquals(List.of(new RemoteStore.RemoteFile("shop_1.sql", DATA.length)), store.list(), "nested keys aren't listed");
                objects.remove("db/nested/other.sql");
                exercise(store);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void azureConnectionFailureSurfacesAsAnError() {
        DestinationConfig d = new DestinationConfig();
        d.setName("azure");
        d.setType("azure");
        d.setContainer("c");
        d.setConnectionString("not a connection string");
        assertThrows(RuntimeException.class, () -> AzureStore.connect(d));
    }

    @Test
    void objectKeysAndPrefixes() {
        assertEquals("a.sql", StoreFactory.objectKey("", "a.sql"));
        assertEquals("a.sql", StoreFactory.objectKey(null, "a.sql"));
        assertEquals("db/a.sql", StoreFactory.objectKey("db", "a.sql"));
        assertEquals("db/a.sql", StoreFactory.objectKey("db/", "a.sql"));
        assertEquals("a.sql", StoreFactory.nameUnderPrefix("db/", "db/a.sql"));
        assertEquals("a.sql", StoreFactory.nameUnderPrefix("", "a.sql"));
        assertNull(StoreFactory.nameUnderPrefix("db/", "other/a.sql"));
        assertNull(StoreFactory.nameUnderPrefix("db/", "db/nested/a.sql"));
        assertNull(StoreFactory.nameUnderPrefix("db/", "db/"));
        assertNull(StoreFactory.nameUnderPrefix("db", "dbx/a.sql"));
        assertFalse(StoreFactory.objectKey("db", "a").isEmpty());
    }
}
