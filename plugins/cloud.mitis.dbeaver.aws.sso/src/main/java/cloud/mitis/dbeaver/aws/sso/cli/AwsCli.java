package cloud.mitis.dbeaver.aws.sso.cli;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AwsCli {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern VERSION = Pattern.compile("aws-cli/2\\.(\\d+)\\.\\d+");
    private final String executable;
    private final CommandRunner runner;
    private boolean versionVerified;

    public AwsCli(String executable, CommandRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    public String executable() {
        return executable;
    }

    public void verifyVersion(AuthProgress progress) throws AuthException {
        AuthException.checkCancelled(progress);
        if (versionVerified) {
            return;
        }
        var result = runner.run(List.of(executable, "--version"), Duration.ofSeconds(10), progress, line -> { });
        var matcher = VERSION.matcher(result.stdout() + result.stderr());
        if (result.exitCode() != 0 || !matcher.find() || Integer.parseInt(matcher.group(1)) < 22) {
            throw new AuthException(AuthException.Kind.CONFIGURATION,
                    "AWS CLI v2.22.0 or newer is required for browser sign-in. Select its executable in the connection settings.");
        }
        versionVerified = true;
    }

    public CommandRunner.Result token(RdsConnection connection, AuthProgress progress) throws AuthException {
        return run(List.of("rds", "generate-db-auth-token", "--profile", connection.profile(),
                "--region", connection.region(), "--hostname", connection.hostname(),
                "--port", Integer.toString(connection.port()), "--username", connection.username()),
                COMMAND_TIMEOUT, progress, line -> { });
    }

    public void login(String profile, AuthProgress progress, CommandRunner.OutputListener output) throws AuthException {
        var result = run(List.of("sso", "login", "--profile", profile, "--no-browser"),
                Duration.ofMinutes(5), progress, output);
        if (result.exitCode() != 0) {
            throw new AuthException(AuthException.Kind.LOGIN,
                    "AWS browser sign-in did not complete (exit " + result.exitCode()
                            + "). Check the browser and your SSO profile, then reconnect.");
        }
    }

    public List<String> profiles(AuthProgress progress) throws AuthException {
        verifyVersion(progress);
        var result = run(List.of("configure", "list-profiles"), Duration.ofSeconds(15), progress, line -> { });
        if (result.exitCode() != 0) {
            throw new AuthException(AuthException.Kind.CONFIGURATION,
                    "Could not list AWS profiles. Check your AWS configuration files.");
        }
        return result.stdout().lines().filter(line -> !line.isBlank()).sorted().toList();
    }

    private CommandRunner.Result run(List<String> arguments, Duration timeout,
            AuthProgress progress, CommandRunner.OutputListener output) throws AuthException {
        var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(arguments);
        command.addAll(List.of("--no-cli-pager", "--cli-connect-timeout", "10", "--cli-read-timeout", "30"));
        return runner.run(List.copyOf(command), timeout, progress, output);
    }

    public static boolean needsLogin(CommandRunner.Result result) {
        if (result.exitCode() == 0) {
            return false;
        }
        String error = result.stderr().toLowerCase(Locale.ROOT);
        return error.contains("the sso session associated with this profile has expired or is otherwise invalid")
                || (error.contains("error loading sso token") && error.contains("does not exist"))
                || (error.contains("error when retrieving token from sso")
                    && error.contains("token has expired and refresh failed"))
                || (error.contains("invalidgrantexception") && error.contains("createtoken"));
    }

    public static String tokenValue(CommandRunner.Result result, RdsConnection connection) throws AuthException {
        if (result.exitCode() != 0) {
            String error = result.stderr().toLowerCase(Locale.ROOT);
            String hint = error.contains("could not be found") && error.contains("profile")
                    ? "The selected AWS profile does not exist."
                    : error.contains("could not connect to the endpoint url")
                    ? "AWS could not be reached. Check the network and proxy configuration."
                    : "Check the selected profile, its AWS permissions, region and SSO configuration.";
            throw new AuthException(AuthException.Kind.CREDENTIALS,
                    "AWS CLI could not generate an RDS token (exit " + result.exitCode() + "). " + hint);
        }
        String token = result.stdout().strip();
        if (!token.startsWith(connection.hostname() + ":" + connection.port() + "/?")
                || !token.contains("Action=connect") || !token.contains("X-Amz-Signature=")
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw new AuthException(AuthException.Kind.CREDENTIALS, "AWS CLI returned an invalid RDS authentication token.");
        }
        return token;
    }

    public static AwsCli discover(String configured, CommandRunner runner, AuthProgress progress) throws AuthException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        return discover(candidates(configured, System.getenv(), windows, System.getProperty("user.home")), runner, progress);
    }

    static AwsCli discover(List<String> candidates, CommandRunner runner, AuthProgress progress) throws AuthException {
        var failures = new ArrayList<String>();
        for (String candidate : candidates) {
            AuthException.checkCancelled(progress);
            progress.status("Checking AWS CLI: " + candidate);
            AwsCli cli = new AwsCli(candidate, runner);
            try {
                cli.verifyVersion(progress);
                return cli;
            } catch (AuthException e) {
                if (e.kind() == AuthException.Kind.CANCELLED) {
                    throw e;
                }
                failures.add(candidate + " (" + e.kind().name().toLowerCase(Locale.ROOT) + ")");
            }
        }
        throw new AuthException(AuthException.Kind.CONFIGURATION,
                "DBeaver could not find a working AWS CLI v2.22.0 or newer. "
                        + "Set its full path in AWS CLI executable. Tried: "
                        + (failures.isEmpty() ? "no executable found in PATH or standard installation directories." : String.join(", ", failures)));
    }

    static List<String> candidates(String configured, Map<String, String> environment, boolean windows, String home) {
        if (configured != null && !configured.isBlank()) {
            return List.of(configured.strip());
        }
        String override = environmentValue(environment, "AWS_CLI_PATH", windows);
        if (override != null && !override.isBlank()) {
            return List.of(override.strip());
        }
        var candidates = new LinkedHashSet<String>();
        String binary = windows ? "aws.exe" : "aws";
        String path = environmentValue(environment, "PATH", windows);
        if (path != null) {
            for (String directory : path.split(windows ? ";" : ":")) {
                if (windows && directory.startsWith("\"") && directory.endsWith("\"") && directory.length() > 1) {
                    directory = directory.substring(1, directory.length() - 1);
                }
                addCandidate(candidates, directory, binary);
            }
        }
        if (windows) {
            addCandidate(candidates, environmentValue(environment, "ProgramFiles", true), "Amazon", "AWSCLIV2", binary);
            addCandidate(candidates, environmentValue(environment, "ProgramW6432", true), "Amazon", "AWSCLIV2", binary);
        } else {
            addCandidate(candidates, environment.get("XDG_BIN_HOME"), binary);
            addCandidate(candidates, home, ".local", "bin", binary);
            addCandidate(candidates, "/usr/local/bin", binary);
            addCandidate(candidates, "/opt/homebrew/bin", binary);
            addCandidate(candidates, "/usr/bin", binary);
            addCandidate(candidates, "/snap/bin", binary);
        }
        return List.copyOf(candidates);
    }

    private static void addCandidate(Set<String> candidates, String directory, String... components) {
        if (directory == null || directory.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(directory, components).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                candidates.add(path.toString());
            }
        } catch (InvalidPathException e) {
            // A malformed PATH entry must not hide later installations.
        }
    }

    private static String environmentValue(Map<String, String> environment, String key, boolean windows) {
        if (!windows) {
            return environment.get(key);
        }
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
