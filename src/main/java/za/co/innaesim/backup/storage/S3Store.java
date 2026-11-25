package za.co.innaesim.backup.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.model.DestinationConfig;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Object;

/** AWS S3 and S3-compatible services. Credentials default to the standard AWS chain. */
public final class S3Store implements RemoteStore {
    /** A single PUT tops out at 5 GB, so anything large goes up in parts. */
    private static final long MULTIPART_THRESHOLD = 1024L * 1024 * 1024;
    private static final long MIN_PART_SIZE = 64L * 1024 * 1024;
    private static final long MAX_PARTS = 9_000; // S3's hard limit is 10,000

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    private S3Store(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    /**
     * Creates a client. No request is made until the store is first used.
     *
     * @param d the destination's settings
     * @return the store
     */
    public static S3Store connect(DestinationConfig d) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(d.getRegion()))
                .httpClient(UrlConnectionHttpClient.create());
        if (d.getAccessKey() != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(d.getAccessKey(), d.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (d.getEndpoint() != null) {
            // Most S3-compatible services need path-style addressing.
            builder.endpointOverride(URI.create(d.getEndpoint())).forcePathStyle(true);
        }
        return new S3Store(builder.build(), d.getBucket(), d.getPrefix());
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        String key = StoreFactory.objectKey(prefix, name);
        long size = Files.size(file);
        try {
            if (size < MULTIPART_THRESHOLD) {
                s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromFile(file));
            } else {
                uploadInParts(file, key, size);
            }
        } catch (SdkException e) {
            throw new IOException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    private void uploadInParts(Path file, String key, long size) throws IOException {
        long partSize = Math.max(MIN_PART_SIZE, size / MAX_PARTS + 1);
        String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
        try (InputStream in = Files.newInputStream(file)) {
            List<CompletedPart> parts = new ArrayList<>();
            long remaining = size;
            for (int number = 1; remaining > 0; number++) {
                long length = Math.min(partSize, remaining);
                byte[] data = in.readNBytes((int) length);
                if (data.length != length) throw new IOException("File changed during upload");
                int partNumber = number;
                String etag = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber),
                        RequestBody.fromBytes(data)).eTag();
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                remaining -= length;
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId).multipartUpload(m -> m.parts(parts)));
        } catch (IOException | RuntimeException e) {
            // Otherwise the uploaded parts stay in the bucket, and keep costing money.
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
            throw e;
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        String listPrefix = prefix == null || prefix.isEmpty() ? null : (prefix.endsWith("/") ? prefix : prefix + "/");
        List<RemoteFile> files = new ArrayList<>();
        try {
            for (S3Object object : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(listPrefix)).contents()) {
                String name = StoreFactory.nameUnderPrefix(prefix, object.key());
                if (name != null) files.add(new RemoteFile(name, object.size()));
            }
        } catch (SdkException e) {
            throw new IOException("S3 list failed: " + e.getMessage(), e);
        }
        return files;
    }

    @Override
    public void download(String name, Path target) throws IOException {
        try {
            Files.deleteIfExists(target);
            s3.getObject(b -> b.bucket(bucket).key(StoreFactory.objectKey(prefix, name)), ResponseTransformer.toFile(target));
        } catch (SdkException e) {
            throw new IOException("S3 download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(StoreFactory.objectKey(prefix, name)));
        } catch (SdkException e) {
            throw new IOException("S3 delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
