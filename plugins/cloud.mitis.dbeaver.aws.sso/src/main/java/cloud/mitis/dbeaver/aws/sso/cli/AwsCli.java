package cloud.mitis.dbeaver.aws.sso.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AwsCli {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern VERSION = Pattern.compile("aws-cli/2\\.(\\d+)\\.\\d+");
    private final String executable;
    private final CommandRunner runner;

    public AwsCli(String executable, CommandRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    public String executable() {
        return executable;
    }

    public void verifyVersion(AuthProgress progress) throws AuthException {
        var result = runner.run(List.of(executable, "--version"), Duration.ofSeconds(10), progress, line -> { });
        var matcher = VERSION.matcher(result.stdout() + result.stderr());
        if (result.exitCode() != 0 || !matcher.find() || Integer.parseInt(matcher.group(1)) < 22) {
            throw new AuthException(AuthException.Kind.CONFIGURATION,
                    "AWS CLI v2.22.0 or newer is required for browser sign-in. Select its executable in the connection settings.");
        }
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

    public static String locate(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        String override = System.getenv("AWS_CLI_PATH");
        if (override != null && !override.isBlank()) {
            return override;
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        var candidates = new ArrayList<Path>();
        if (windows) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                candidates.add(Path.of(programFiles, "Amazon", "AWSCLIV2", "aws.exe"));
            }
        } else {
            candidates.addAll(List.of(Path.of("/usr/local/bin/aws"), Path.of("/opt/homebrew/bin/aws"),
                    Path.of(System.getProperty("user.home"), ".local/bin/aws"), Path.of("/usr/bin/aws")));
        }
        return candidates.stream().filter(Files::isExecutable).map(Path::toString).findFirst()
                .orElse(windows ? "aws.exe" : "aws");
    }
}
