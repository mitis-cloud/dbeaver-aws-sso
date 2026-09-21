package cloud.mitis.dbeaver.aws.sso.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class ProcessRunnerTest {
    @TempDir
    Path directory;

    @Test
    void drainsBothPipesWithoutDeadlock() throws Exception {
        var result = new ProcessRunner().run(command("streams"), Duration.ofSeconds(10), () -> false, line -> { });
        assertEquals(0, result.exitCode());
        assertEquals(131_072, result.stdout().length());
        assertEquals(131_072, result.stderr().length());
        assertFalse(result.toString().contains("ooo"));
    }

    @Test
    void timeoutTerminatesCliProcess() throws Exception {
        Path pidFile = directory.resolve("pid");
        AuthException failure = assertThrows(AuthException.class, () -> new ProcessRunner().run(
                command("hang", pidFile.toString()), Duration.ofSeconds(2), () -> false, line -> { }));
        assertEquals(AuthException.Kind.TIMEOUT, failure.kind());
        assertTerminated(pidFile);
    }

    @Test
    void cancellationTerminatesCliProcess() throws Exception {
        Path pidFile = directory.resolve("pid");
        AtomicBoolean cancelled = new AtomicBoolean();
        AuthException failure = assertThrows(AuthException.class, () -> new ProcessRunner().run(
                command("hang", pidFile.toString()), Duration.ofSeconds(10), cancelled::get, line -> cancelled.set(true)));
        assertEquals(AuthException.Kind.CANCELLED, failure.kind());
        assertTerminated(pidFile);
    }

    @Test
    void browserFailureStopsLoginPrompt() throws Exception {
        Path pidFile = directory.resolve("pid");
        AuthException failure = assertThrows(AuthException.class, () -> new ProcessRunner().run(
                command("hang", pidFile.toString()), Duration.ofSeconds(10), () -> false, line -> {
                    throw new AuthException(AuthException.Kind.LOGIN, "No browser available");
                }));
        assertEquals(AuthException.Kind.LOGIN, failure.kind());
        assertTerminated(pidFile);
    }

    @Test
    void outputIsBounded() throws Exception {
        AuthException failure = assertThrows(AuthException.class, () -> new ProcessRunner().run(
                command("excessive"), Duration.ofSeconds(10), () -> false, line -> { }));
        assertEquals(AuthException.Kind.PROCESS, failure.kind());
    }

    @Test
    void passesArgumentsWithoutShellAndDisablesInteractiveCliPrompts() throws Exception {
        String argument = "company name; $(echo no) & whoami";
        var result = new ProcessRunner().run(command("environment", argument),
                Duration.ofSeconds(10), () -> false, line -> { });
        assertEquals(List.of("off", "pager=", argument), result.stdout().lines().toList());
    }

    @Test
    void cancelledCommandDoesNotStartProcess() throws Exception {
        Path pidFile = directory.resolve("pid");
        assertThrows(AuthException.class, () -> new ProcessRunner().run(command("hang", pidFile.toString()),
                Duration.ofSeconds(10), () -> true, line -> { }));
        assertFalse(Files.exists(pidFile));
    }

    private void assertTerminated(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile));
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) {
            process.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(process.get().isAlive());
        }
    }

    private static List<String> command(String... args) throws Exception {
        String binary = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
        String classes = Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = new ArrayList<>(List.of(java, "-cp", classes, ProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }
}
