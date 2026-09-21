package cloud.mitis.dbeaver.aws.sso.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class SsoAuthenticationTest {
    private static final RdsConnection CONNECTION = new RdsConnection(
            "company dev", "eu-west-1", "db.example.eu-west-1.rds.amazonaws.com", 5432, "db user");
    private static final String TOKEN = CONNECTION.hostname() + ":5432/?Action=connect&DBUser=db%20user&X-Amz-Signature=secret";
    private static final String LOGIN_URL = "https://oidc.eu-west-1.amazonaws.com/authorize?response_type=code&state=nonce";
    private static final CommandRunner.Result VERSION = new CommandRunner.Result(0, "aws-cli/2.22.0 Python/3\n", "");
    private static final CommandRunner.Result EXPIRED = new CommandRunner.Result(255, "",
            "Error when retrieving token from sso: Token has expired and refresh failed");
    private static final AuthProgress PROGRESS = () -> false;

    @Test
    void validSessionGeneratesTokenWithoutBrowser() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        CommandRunner runner = (command, timeout, progress, output) -> {
            commands.add(command);
            return command.contains("--version") ? VERSION : new CommandRunner.Result(0, TOKEN + "\n", "");
        };
        assertEquals(TOKEN, new SsoAuthentication().authenticate(new AwsCli("/path with spaces/aws", runner),
                CONNECTION, PROGRESS, url -> fail("Browser must remain closed")));
        assertEquals(2, commands.size());
        List<String> tokenCommand = commands.get(1);
        assertEquals("company dev", tokenCommand.get(tokenCommand.indexOf("--profile") + 1));
        assertEquals("db user", tokenCommand.get(tokenCommand.indexOf("--username") + 1));
        assertEquals(CONNECTION.hostname(), tokenCommand.get(tokenCommand.indexOf("--hostname") + 1));
        assertEquals("/path with spaces/aws", tokenCommand.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Error when retrieving token from sso: Token has expired and refresh failed",
            "Error loading SSO Token: Token for company does not exist",
            "The SSO session associated with this profile has expired or is otherwise invalid. To refresh this SSO session run aws sso login",
            "An error occurred (InvalidGrantException) when calling the CreateToken operation"
    })
    void expiredAndMissingSessionsOpenBrowserAndRetry(String error) throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        List<URI> browser = new ArrayList<>();
        CommandRunner runner = (command, timeout, progress, output) -> {
            if (command.contains("--version")) {
                return VERSION;
            }
            if (command.contains("login")) {
                logins.incrementAndGet();
                assertTrue(command.contains("--no-browser"));
                assertEquals("company dev", command.get(command.indexOf("--profile") + 1));
                output.line("Please open the following URL:");
                output.line(LOGIN_URL);
                output.line(LOGIN_URL);
                return new CommandRunner.Result(0, "Successfully logged in", "");
            }
            return tokens.incrementAndGet() == 1
                    ? new CommandRunner.Result(255, "", error) : new CommandRunner.Result(0, TOKEN, "");
        };
        assertEquals(TOKEN, new SsoAuthentication().authenticate(new AwsCli("aws", runner), CONNECTION, PROGRESS, browser::add));
        assertEquals(2, tokens.get());
        assertEquals(1, logins.get());
        assertEquals(List.of(URI.create(LOGIN_URL)), browser);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Could not connect to the endpoint URL: https://portal.sso.eu-west-1.amazonaws.com",
            "An error occurred (ForbiddenException) when calling GetRoleCredentials: No access",
            "The config profile (missing) could not be found",
            "Unable to locate credentials",
            "Error when retrieving token from sso: endpoint is unreachable",
            "Unexpected failure accessToken=secret-secret"
    })
    void unrelatedErrorsNeverStartLoginOrExposeCliOutput(String error) {
        CommandRunner runner = (command, timeout, progress, output) -> {
            assertFalse(command.contains("login"));
            return command.contains("--version") ? VERSION : new CommandRunner.Result(255, "secret-output", error);
        };
        AuthException failure = assertThrows(AuthException.class, () -> new SsoAuthentication().authenticate(
                new AwsCli("aws", runner), CONNECTION, PROGRESS, url -> fail("Unexpected browser")));
        assertEquals(AuthException.Kind.CREDENTIALS, failure.kind());
        assertFalse(failure.toString().contains("secret"));
        assertNull(failure.getCause());
    }

    @Test
    void unsuccessfulLoginDoesNotGenerateAnotherToken() {
        AtomicInteger tokens = new AtomicInteger();
        CommandRunner runner = (command, timeout, progress, output) -> {
            if (command.contains("--version")) {
                return VERSION;
            }
            if (command.contains("login")) {
                return new CommandRunner.Result(255, "sensitive-url", "secret");
            }
            tokens.incrementAndGet();
            return EXPIRED;
        };
        AuthException failure = assertThrows(AuthException.class, () -> new SsoAuthentication().authenticate(
                new AwsCli("aws", runner), CONNECTION, PROGRESS, url -> { }));
        assertEquals(AuthException.Kind.LOGIN, failure.kind());
        assertEquals(1, tokens.get());
    }

    @Test
    void retryIsBoundedWhenLoginCannotRepairSession() {
        AtomicInteger logins = new AtomicInteger();
        CommandRunner runner = (command, timeout, progress, output) -> {
            if (command.contains("--version")) {
                return VERSION;
            }
            if (command.contains("login")) {
                logins.incrementAndGet();
                return new CommandRunner.Result(0, "", "");
            }
            return EXPIRED;
        };
        assertThrows(AuthException.class, () -> new SsoAuthentication().authenticate(
                new AwsCli("aws", runner), CONNECTION, PROGRESS, url -> { }));
        assertEquals(1, logins.get());
    }

    @Test
    void concurrentConnectionsShareOneLogin() throws Exception {
        concurrentLogin(false);
    }

    @Test
    void concurrentConnectionsShareLoginFailure() throws Exception {
        concurrentLogin(true);
    }

    private void concurrentLogin(boolean failLogin) throws Exception {
        CountDownLatch initialTokens = new CountDownLatch(2);
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger browsers = new AtomicInteger();
        CommandRunner runner = (command, timeout, progress, output) -> {
            if (command.contains("--version")) {
                return VERSION;
            }
            if (command.contains("login")) {
                logins.incrementAndGet();
                output.line(LOGIN_URL);
                return new CommandRunner.Result(failLogin ? 255 : 0, "", "");
            }
            if (tokens.incrementAndGet() <= 2) {
                initialTokens.countDown();
                try {
                    assertTrue(initialTokens.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AuthException(AuthException.Kind.CANCELLED, "Cancelled");
                }
                return EXPIRED;
            }
            return new CommandRunner.Result(0, TOKEN, "");
        };
        SsoAuthentication authentication = new SsoAuthentication();
        AwsCli cli = new AwsCli("aws", runner);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of(
                    executor.submit(() -> authenticateOutcome(authentication, cli, browsers)),
                    executor.submit(() -> authenticateOutcome(authentication, cli, browsers)));
            for (var task : tasks) {
                assertEquals(failLogin ? "LOGIN" : TOKEN, task.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, logins.get());
        assertEquals(1, browsers.get());
    }

    private String authenticateOutcome(SsoAuthentication authentication, AwsCli cli, AtomicInteger browsers) {
        try {
            return authentication.authenticate(cli, CONNECTION, PROGRESS, url -> browsers.incrementAndGet());
        } catch (AuthException e) {
            return e.kind().name();
        }
    }

    @Test
    void everyNewConnectionGetsFreshToken() throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        CommandRunner runner = (command, timeout, progress, output) -> command.contains("--version")
                ? VERSION : new CommandRunner.Result(0, TOKEN + tokens.incrementAndGet(), "");
        SsoAuthentication authentication = new SsoAuthentication();
        AwsCli cli = new AwsCli("aws", runner);
        assertEquals(TOKEN + "1", authentication.authenticate(cli, CONNECTION, PROGRESS, url -> { }));
        assertEquals(TOKEN + "2", authentication.authenticate(cli, CONNECTION, PROGRESS, url -> { }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-cli/1.44.81", "aws-cli/2.21.0", "unknown"})
    void unsupportedCliFailsBeforeAuthentication(String version) {
        CommandRunner runner = (command, timeout, progress, output) -> {
            assertEquals(List.of("aws", "--version"), command);
            return new CommandRunner.Result(0, version, "");
        };
        AuthException failure = assertThrows(AuthException.class, () -> new SsoAuthentication().authenticate(
                new AwsCli("aws", runner), CONNECTION, PROGRESS, url -> fail("Unexpected browser")));
        assertEquals(AuthException.Kind.CONFIGURATION, failure.kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "secret", "host:5432/?Action=connect&X-Amz-Signature=secret", "first\nsecond"})
    void invalidTokenOutputIsRejectedWithoutExposure(String token) {
        AuthException failure = assertThrows(AuthException.class,
                () -> AwsCli.tokenValue(new CommandRunner.Result(0, token, ""), CONNECTION));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void acceptsOnlyAwsAuthorizationUrls() {
        assertEquals(URI.create(LOGIN_URL), SsoAuthentication.authorizationUrl(LOGIN_URL));
        assertNotNull(SsoAuthentication.authorizationUrl(
                "https://oidc.cn-north-1.amazonaws.com.cn/authorize?response_type=code"));
        assertNull(SsoAuthentication.authorizationUrl("https://example.com/authorize?response_type=code"));
        assertNull(SsoAuthentication.authorizationUrl("https://oidc.eu-west-1.amazonaws.com.evil.test/authorize?response_type=code"));
        assertNull(SsoAuthentication.authorizationUrl("https://docs.aws.amazon.com/cli/"));
        assertNull(SsoAuthentication.authorizationUrl("http://oidc.eu-west-1.amazonaws.com/authorize?response_type=code"));
        assertNull(SsoAuthentication.authorizationUrl("https://device.sso.eu-west-1.amazonaws.com/"));
        assertNotNull(SsoAuthentication.authorizationUrl("https://device.sso.eu-west-1.amazonaws.com/?user_code=ABCD-EFGH"));
        assertNotNull(SsoAuthentication.authorizationUrl("https://company.awsapps.com/start/#/device?user_code=ABCD-EFGH"));
    }
}
