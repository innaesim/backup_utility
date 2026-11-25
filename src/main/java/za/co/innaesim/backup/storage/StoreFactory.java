package za.co.innaesim.backup.storage;

import java.io.IOException;

import za.co.innaesim.backup.model.DestinationConfig;

/** Connects to a destination. */
@FunctionalInterface
public interface StoreFactory {
    /**
     * Connects to a destination.
     *
     * @param destination the destination's settings
     * @return an open connection, for the caller to close
     * @throws IOException if connecting fails
     */
    RemoteStore open(DestinationConfig destination) throws IOException;

    /**
     * Gives the factory for the real storage services.
     *
     * @return a factory that picks the store from the destination's type
     */
    static StoreFactory standard() {
        return destination -> switch (destination.getType()) {
            case "ftp" -> FtpStore.connect(destination);
            case "sftp" -> SftpStore.connect(destination);
            case "s3" -> S3Store.connect(destination);
            case "azure" -> AzureStore.connect(destination);
            default -> throw new IOException("Unsupported destination type " + destination.getType());
        };
    }

    /**
     * Joins a key prefix and file name for object stores, which have no real folders.
     *
     * @param prefix the prefix; may be null or empty
     * @param name the file name
     * @return the object key
     */
    static String objectKey(String prefix, String name) {
        if (prefix == null || prefix.isEmpty()) return name;
        return prefix.endsWith("/") ? prefix + name : prefix + "/" + name;
    }

    /**
     * Extracts the file name from an object key.
     *
     * @param prefix the prefix; may be null or empty
     * @param key the object key
     * @return the file name, or null when the key isn't directly inside the prefix
     */
    static String nameUnderPrefix(String prefix, String key) {
        String base = prefix == null || prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        if (!key.startsWith(base)) return null;
        String name = key.substring(base.length());
        return name.isEmpty() || name.contains("/") ? null : name;
    }
}
