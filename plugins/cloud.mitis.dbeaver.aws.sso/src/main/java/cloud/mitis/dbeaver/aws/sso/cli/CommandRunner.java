package cloud.mitis.dbeaver.aws.sso.cli;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface CommandRunner {
    Result run(List<String> command, Duration timeout, AuthProgress progress, OutputListener output) throws AuthException;

    @FunctionalInterface
    interface OutputListener {
        void line(String line) throws AuthException;
    }

    record Result(int exitCode, String stdout, String stderr) {
        @Override
        public String toString() {
            return "Command result (exit " + exitCode + ")";
        }
    }
}
