package cloud.mitis.dbeaver.aws.sso;

import java.net.URI;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import cloud.mitis.dbeaver.aws.sso.cli.AuthException;
import cloud.mitis.dbeaver.aws.sso.cli.AuthProgress;
import cloud.mitis.dbeaver.aws.sso.cli.AwsCli;
import cloud.mitis.dbeaver.aws.sso.cli.ProcessRunner;
import cloud.mitis.dbeaver.aws.sso.cli.RdsConnection;
import cloud.mitis.dbeaver.aws.sso.cli.SsoAuthentication;

public final class SsoAuthModel extends AuthModelDatabaseNative<SsoCredentials> {
    private static final SsoAuthentication AUTHENTICATION = new SsoAuthentication();

    @Override
    public SsoCredentials createCredentials() {
        return new SsoCredentials();
    }

    @Override
    public SsoCredentials loadCredentials(DBPDataSourceContainer dataSource, DBPConnectionConfiguration configuration) {
        SsoCredentials credentials = createCredentials();
        credentials.setUserName(configuration.getUserName());
        credentials.profile = value(configuration, SsoCredentials.PROFILE, "default");
        credentials.region = value(configuration, SsoCredentials.REGION, "");
        credentials.cliPath = value(configuration, SsoCredentials.CLI_PATH, "");
        credentials.hostname = value(configuration, SsoCredentials.HOSTNAME, "");
        credentials.port = value(configuration, SsoCredentials.PORT, "");
        return credentials;
    }

    @Override
    public void saveCredentials(DBPDataSourceContainer dataSource, DBPConnectionConfiguration configuration,
            SsoCredentials credentials) {
        configuration.setUserName(credentials.getUserName());
        configuration.setUserPassword(null);
        configuration.setAuthProperty(SsoCredentials.PROFILE, credentials.profile);
        configuration.setAuthProperty(SsoCredentials.REGION, credentials.region);
        configuration.setAuthProperty(SsoCredentials.CLI_PATH, credentials.cliPath);
        configuration.setAuthProperty(SsoCredentials.HOSTNAME, credentials.hostname);
        configuration.setAuthProperty(SsoCredentials.PORT, credentials.port);
    }

    @Override
    public Object initAuthentication(DBRProgressMonitor monitor, DBPDataSource dataSource, SsoCredentials credentials,
            DBPConnectionConfiguration configuration, Properties properties) throws DBException {
        DBPDataSourceContainer container = dataSource.getContainer();
        DBPConnectionConfiguration original = container.getConnectionConfiguration();
        String hostname = credentials.hostname.isBlank() ? original.getHostName() : credentials.hostname;
        String port = credentials.port.isBlank() ? original.getHostPort() : credentials.port;
        AuthProgress progress = new AuthProgress() {
            @Override
            public boolean isCancelled() {
                return monitor.isCanceled();
            }

            @Override
            public void status(String message) {
                monitor.subTask(message);
            }
        };
        try {
            RdsConnection connection = new RdsConnection(credentials.profile, credentials.region,
                    hostname, Integer.parseInt(port), credentials.getUserName());
            AwsCli cli = new AwsCli(AwsCli.locate(credentials.cliPath), new ProcessRunner());
            String token = AUTHENTICATION.authenticate(cli, connection, progress, uri -> openBrowser(uri, progress));
            collectConnectionProperties(container, credentials, configuration, properties, false);
            applyTlsDefaults(container.getDriver().getDriverClassName(), properties);
            properties.setProperty(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD, token);
            return credentials;
        } catch (IllegalArgumentException e) {
            throw new DBException("Specify the RDS hostname, port, database user, AWS profile and RDS region. "
                    + "For URL-based or manually forwarded connections, set the signing endpoint fields.");
        } catch (AuthException e) {
            throw new DBException(e.getMessage());
        }
    }

    @Override
    public void collectConnectionProperties(DBPDataSourceContainer container, SsoCredentials credentials,
            DBPConnectionConfiguration configuration, Properties properties, boolean collectSecuredProps) {
        if (credentials.getUserName() != null) {
            properties.setProperty(DBConstants.DATA_SOURCE_PROPERTY_USER, credentials.getUserName());
        }
        properties.remove(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD);
    }

    @Override
    public void endAuthentication(DBPDataSourceContainer dataSource, DBPConnectionConfiguration configuration,
            Properties properties) {
        properties.remove(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD);
    }

    @Override
    public boolean isUserPasswordApplicable() {
        return false;
    }

    @Override
    protected boolean isUserPasswordNeeded(DBPDataSourceContainer container) {
        return false;
    }

    static void applyTlsDefaults(String driverClass, Properties properties) {
        String driver = driverClass.toLowerCase(Locale.ROOT);
        if (driver.contains("postgresql")) {
            properties.putIfAbsent("sslmode", "verify-full");
        } else if (driver.contains("mariadb")) {
            properties.putIfAbsent("sslMode", "verify-full");
        } else {
            properties.putIfAbsent("sslMode", "VERIFY_IDENTITY");
        }
    }

    private static void openBrowser(URI uri, AuthProgress progress) throws AuthException {
        AuthException.checkCancelled(progress);
        AtomicBoolean launched = new AtomicBoolean();
        Display display = Display.getDefault();
        if (display.isDisposed()) {
            throw new AuthException(AuthException.Kind.LOGIN, "DBeaver is closing; AWS sign-in was cancelled.");
        }
        display.syncExec(() -> {
            if (!progress.isCancelled()) {
                launched.set(Program.launch(uri.toASCIIString()));
            }
        });
        AuthException.checkCancelled(progress);
        if (!launched.get()) {
            throw new AuthException(AuthException.Kind.LOGIN,
                    "Could not open the sign-in browser. Configure your system's default browser and reconnect.");
        }
    }

    private static String value(DBPConnectionConfiguration configuration, String key, String fallback) {
        String value = configuration.getAuthProperty(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
