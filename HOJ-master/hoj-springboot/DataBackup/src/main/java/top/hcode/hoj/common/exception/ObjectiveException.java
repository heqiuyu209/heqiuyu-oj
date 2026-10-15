package top.hcode.hoj.common.exception;

/** A business error caught outside the manager's transaction boundary. */
public class ObjectiveException extends RuntimeException {
    private final int status;

    public ObjectiveException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
