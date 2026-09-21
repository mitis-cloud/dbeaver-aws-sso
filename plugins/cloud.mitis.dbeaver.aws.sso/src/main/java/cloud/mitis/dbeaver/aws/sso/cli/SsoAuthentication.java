package cloud.mitis.dbeaver.aws.sso.cli;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class SsoAuthentication {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\"]+");
    private final ConcurrentHashMap<LoginKey, LoginAttempt> attempts = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface Browser {
        void open(URI url) throws AuthException;
    }

    public String authenticate(AwsCli cli, RdsConnection connection, AuthProgress progress, Browser browser)
            throws AuthException {
        LoginKey key = new LoginKey(cli.executable(), connection.profile());
        LoginAttempt attempt = attempts.compute(key, (ignored, existing) -> {
            LoginAttempt value = existing == null ? new LoginAttempt() : existing;
            value.users++;
            return value;
        });
        try {
            progress.status("Resolving AWS credentials and generating the database token");
            cli.verifyVersion(progress);
            var result = cli.token(connection, progress);
            if (!AwsCli.needsLogin(result)) {
                return AwsCli.tokenValue(result, connection);
            }
            login(cli, connection.profile(), attempt, progress, browser);
            progress.status("AWS sign-in complete; generating the database token");
            return AwsCli.tokenValue(cli.token(connection, progress), connection);
        } finally {
            attempts.computeIfPresent(key, (ignored, value) -> --value.users == 0 ? null : value);
        }
    }

    private static void login(AwsCli cli, String profile, LoginAttempt attempt, AuthProgress progress, Browser browser)
            throws AuthException {
        progress.status("Waiting for AWS IAM Identity Center sign-in in your browser");
        if (attempt.started.compareAndSet(false, true)) {
            try {
                AtomicBoolean opened = new AtomicBoolean();
                cli.login(profile, progress, line -> {
                    URI url = authorizationUrl(line);
                    if (url != null && opened.compareAndSet(false, true)) {
                        AuthException.checkCancelled(progress);
                        browser.open(url);
                    }
                });
                attempt.completed.complete(null);
            } catch (AuthException e) {
                attempt.completed.completeExceptionally(e);
                throw e;
            } catch (RuntimeException e) {
                var failure = new AuthException(AuthException.Kind.LOGIN, "Could not complete AWS browser sign-in.");
                attempt.completed.completeExceptionally(failure);
                throw failure;
            }
        } else {
            while (true) {
                AuthException.checkCancelled(progress);
                try {
                    attempt.completed.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AuthException(AuthException.Kind.CANCELLED, "AWS authentication cancelled.");
                } catch (ExecutionException e) {
                    throw (AuthException) e.getCause();
                }
            }
        }
    }

    static URI authorizationUrl(String line) {
        var matcher = URL.matcher(line);
        while (matcher.find()) {
            try {
                URI uri = URI.create(matcher.group());
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                boolean awsHost = host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn");
                boolean awsPortal = host.endsWith(".awsapps.com") || host.endsWith(".awsapps.cn");
                boolean authorizationCode = awsHost && uri.getPath().equals("/authorize")
                        && uri.getRawQuery() != null && uri.getRawQuery().contains("response_type=code");
                boolean deviceCode = (awsHost && host.startsWith("device.sso.")
                        && uri.getRawQuery() != null && uri.getRawQuery().contains("user_code="))
                        || (awsPortal && uri.getRawFragment() != null
                            && uri.getRawFragment().startsWith("/device?")
                            && uri.getRawFragment().contains("user_code="));
                if (uri.getUserInfo() == null && (authorizationCode || deviceCode)) {
                    return uri;
                }
            } catch (IllegalArgumentException e) {
            }
        }
        return null;
    }

    private record LoginKey(String executable, String profile) {
    }

    private static final class LoginAttempt {
        private int users;
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
    }
}
