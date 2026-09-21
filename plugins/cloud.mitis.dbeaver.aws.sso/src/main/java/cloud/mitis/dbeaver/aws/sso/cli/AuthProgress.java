package cloud.mitis.dbeaver.aws.sso.cli;

public interface AuthProgress {
    boolean isCancelled();

    default void status(String message) {
    }
}
