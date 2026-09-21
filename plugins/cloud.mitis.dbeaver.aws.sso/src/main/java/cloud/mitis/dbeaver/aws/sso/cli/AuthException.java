package cloud.mitis.dbeaver.aws.sso.cli;

public final class AuthException extends Exception {
    public enum Kind { CONFIGURATION, PROCESS, TIMEOUT, CANCELLED, LOGIN, CREDENTIALS }

    private final Kind kind;

    public AuthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static void checkCancelled(AuthProgress progress) throws AuthException {
        if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new AuthException(Kind.CANCELLED, "AWS authentication cancelled.");
        }
    }
}
