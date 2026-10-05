package cloud.mitis.dbeaver.aws.sso.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AwsCliDiscoveryTest {
    private static final AuthProgress PROGRESS = () -> false;
    private static final CommandRunner.Result SUPPORTED = new CommandRunner.Result(0, "aws-cli/2.38.0 Python/3\n", "");

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"aws-cli/1.44.81", "aws-cli/2.21.0", "not AWS CLI"})
    void incompatibleFirstInstallDoesNotHideWorkingInstall(String version) throws Exception {
        List<String> attempts = new ArrayList<>();
        CommandRunner runner = (command, timeout, progress, output) -> {
            assertEquals(List.of(command.getFirst(), "--version"), command);
            attempts.add(command.getFirst());
            return command.getFirst().equals("old/aws")
                    ? new CommandRunner.Result(0, version, "") : SUPPORTED;
        };
        AwsCli cli = AwsCli.discover(List.of("old/aws", "new/aws"), runner, PROGRESS);
        cli.verifyVersion(PROGRESS);
        assertEquals("new/aws", cli.executable());
        assertEquals(List.of("old/aws", "new/aws"), attempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESS", "TIMEOUT"})
    void unusableInstallDoesNotStopDiscovery(String kind) throws Exception {
        CommandRunner runner = (command, timeout, progress, output) -> {
            if (command.getFirst().equals("broken/aws")) {
                throw new AuthException(AuthException.Kind.valueOf(kind), "unusable");
            }
            return SUPPORTED;
        };
        assertEquals("working/aws", AwsCli.discover(List.of("broken/aws", "working/aws"), runner, PROGRESS).executable());
    }

    @Test
    void failureIdentifiesAttemptsWithoutExposingProcessOutput() {
        AuthException failure = assertThrows(AuthException.class, () -> AwsCli.discover(
                List.of("old/aws"), (command, timeout, progress, output) ->
                        new CommandRunner.Result(1, "secret stdout", "secret stderr"), PROGRESS));
        assertEquals(AuthException.Kind.CONFIGURATION, failure.kind());
        assertTrue(failure.getMessage().contains("old/aws"));
        assertTrue(failure.getMessage().contains("v2.22.0"));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void missingInstallGivesManualPathInstruction() {
        AuthException failure = assertThrows(AuthException.class, () -> AwsCli.discover(List.of(),
                (command, timeout, progress, output) -> { throw new AssertionError("Unexpected process"); }, PROGRESS));
        assertTrue(failure.getMessage().contains("Set its full path"));
        assertTrue(failure.getMessage().contains("no executable found"));
    }

    @Test
    void cancelledDiscoveryDoesNotTryAnotherExecutable() {
        List<String> attempts = new ArrayList<>();
        AuthException failure = assertThrows(AuthException.class, () -> AwsCli.discover(
                List.of("first/aws", "second/aws"), (command, timeout, progress, output) -> {
                    attempts.add(command.getFirst());
                    throw new AuthException(AuthException.Kind.CANCELLED, "Cancelled");
                }, PROGRESS));
        assertEquals(AuthException.Kind.CANCELLED, failure.kind());
        assertEquals(List.of("first/aws"), attempts);
    }

    @Test
    void alreadyCancelledDiscoveryDoesNotStartProcess() {
        AuthException failure = assertThrows(AuthException.class, () -> AwsCli.discover(List.of("aws"),
                (command, timeout, progress, output) -> { throw new AssertionError("Unexpected process"); }, () -> true));
        assertEquals(AuthException.Kind.CANCELLED, failure.kind());
    }

    @Test
    void explicitSelectionNeverFallsBackToAnotherInstallation() {
        List<String> candidates = AwsCli.candidates(" /custom path/aws ",
                Map.of("AWS_CLI_PATH", "/different/aws", "PATH", "/bin"), false, directory.toString());
        assertEquals(List.of("/custom path/aws"), candidates);
        List<String> attempts = new ArrayList<>();
        assertThrows(AuthException.class, () -> AwsCli.discover(candidates, (command, timeout, progress, output) -> {
            attempts.add(command.getFirst());
            throw new AuthException(AuthException.Kind.PROCESS, "Missing executable");
        }, PROGRESS));
        assertEquals(candidates, attempts);
    }

    @Test
    void environmentOverrideIsExplicitAndWindowsKeysIgnoreCase() {
        assertEquals(List.of("C:\\custom path\\aws.exe"), AwsCli.candidates("",
                Map.of("aws_cli_path", " C:\\custom path\\aws.exe ", "Path", "ignored"), true, directory.toString()));
    }

    @Test
    void userLocalInstallerIsDiscoveredWithoutDesktopPathEntry() throws Exception {
        Path executable = executable(directory.resolve(".local/bin/aws"));
        AwsCli cli = AwsCli.discover(AwsCli.candidates("", Map.of("PATH", ""), false, directory.toString()),
                (command, timeout, progress, output) -> {
                    assertEquals(executable.toString(), command.getFirst());
                    return SUPPORTED;
                }, PROGRESS);
        assertEquals(executable.toString(), cli.executable());
    }

    @Test
    void installerXdgBinOverrideIsRespected() throws Exception {
        Path executable = executable(directory.resolve("custom bin/aws"));
        List<String> candidates = AwsCli.candidates("", Map.of("XDG_BIN_HOME", executable.getParent().toString()),
                false, directory.toString());
        assertEquals(executable.toString(), candidates.getFirst());
    }

    @Test
    void windowsPathWithSpacesAndQuotesIsSearchedBeforeProgramFiles() throws Exception {
        Path first = executable(directory.resolve("first install/aws.exe"));
        Path second = executable(directory.resolve("second install/aws.exe"));
        Path standard = executable(directory.resolve("Program Files/Amazon/AWSCLIV2/aws.exe"));
        List<String> candidates = AwsCli.candidates("", Map.of(
                "Path", "\"" + first.getParent() + "\";;" + second.getParent() + ";" + first.getParent(),
                "PROGRAMFILES", directory.resolve("Program Files").toString()), true, directory.toString());
        assertEquals(List.of(first.toString(), second.toString(), standard.toString()), candidates);
    }

    @Test
    void windowsStandardInstallIsDiscoveredWithoutUpdatedPath() throws Exception {
        Path standard = executable(directory.resolve("Program Files/Amazon/AWSCLIV2/aws.exe"));
        List<String> candidates = AwsCli.candidates("", Map.of(
                "ProgramFiles", directory.resolve("Program Files (x86)").toString(),
                "ProgramW6432", directory.resolve("Program Files").toString()), true, directory.toString());
        assertEquals(List.of(standard.toString()), candidates);
        assertEquals(standard.toString(), AwsCli.discover(candidates,
                (command, timeout, progress, output) -> SUPPORTED, PROGRESS).executable());
    }

    @Test
    void pathDirectoryAndMalformedEntryDoNotHideExecutable() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        String binary = windows ? "aws.exe" : "aws";
        String separator = windows ? ";" : ":";
        Path notExecutable = directory.resolve("first").resolve(binary);
        Files.createDirectories(notExecutable);
        Path executable = executable(directory.resolve("second").resolve(binary));
        List<String> candidates = AwsCli.candidates("", Map.of(
                "PATH", "invalid\u0000path" + separator + notExecutable.getParent() + separator + executable.getParent()),
                windows, directory.toString());
        assertEquals(executable.toString(), candidates.getFirst());
        assertFalse(candidates.contains(notExecutable.toString()));
    }

    private static Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "fixture");
        assertTrue(path.toFile().setExecutable(true));
        return path.toAbsolutePath().normalize();
    }
}
