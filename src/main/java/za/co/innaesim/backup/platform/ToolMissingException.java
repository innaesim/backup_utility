package za.co.innaesim.backup.platform;

/** A required database tool isn't installed and wasn't installed when offered. */
public class ToolMissingException extends RuntimeException {
    /**
     * Creates the exception.
     *
     * @param message what is missing and how to install it
     */
    public ToolMissingException(String message) {
        super(message);
    }
}
