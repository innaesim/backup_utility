package za.co.innaesim.backup.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.azure.core.exception.AzureException;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import za.co.innaesim.backup.model.DestinationConfig;

/** Azure Blob Storage. The container must already exist. */
public final class AzureStore implements RemoteStore {
    private final BlobContainerClient container;
    private final String prefix;

    private AzureStore(BlobContainerClient container, String prefix) {
        this.container = container;
        this.prefix = prefix;
    }

    /**
     * Creates a client. No request is made until the store is first used.
     *
     * @param d the destination's settings
     * @return the store
     */
    public static AzureStore connect(DestinationConfig d) {
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(d.getConnectionString())
                .buildClient()
                .getBlobContainerClient(d.getContainer());
        return new AzureStore(container, d.getPrefix());
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        try {
            container.getBlobClient(StoreFactory.objectKey(prefix, name)).uploadFromFile(file.toString(), true);
        } catch (AzureException | java.io.UncheckedIOException e) {
            throw new IOException("Azure upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RemoteFile> list() throws IOException {
        String listPrefix = prefix == null || prefix.isEmpty() ? null : (prefix.endsWith("/") ? prefix : prefix + "/");
        List<RemoteFile> files = new ArrayList<>();
        try {
            for (BlobItem blob : container.listBlobs(new ListBlobsOptions().setPrefix(listPrefix), null)) {
                String name = StoreFactory.nameUnderPrefix(prefix, blob.getName());
                if (name != null) files.add(new RemoteFile(name, blob.getProperties().getContentLength()));
            }
        } catch (AzureException e) {
            throw new IOException("Azure list failed: " + e.getMessage(), e);
        }
        return files;
    }

    @Override
    public void download(String name, Path target) throws IOException {
        try {
            container.getBlobClient(StoreFactory.objectKey(prefix, name)).downloadToFile(target.toString(), true);
        } catch (AzureException | java.io.UncheckedIOException e) {
            throw new IOException("Azure download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            container.getBlobClient(StoreFactory.objectKey(prefix, name)).delete();
        } catch (AzureException e) {
            throw new IOException("Azure delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // The Azure client holds no per-store connection to release.
    }
}
