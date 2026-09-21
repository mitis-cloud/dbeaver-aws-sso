package cloud.mitis.dbeaver.aws.sso;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;

import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.junit.jupiter.api.Test;

class SsoAuthModelTest {
    @Test
    void savedConnectionsContainSettingsButNoPassword() {
        SsoAuthModel model = new SsoAuthModel();
        SsoCredentials credentials = model.createCredentials();
        credentials.setUserName("developer");
        credentials.setUserPassword("must-not-be-saved");
        credentials.profile = "company-dev";
        credentials.region = "eu-west-1";
        credentials.hostname = "db.example.rds.amazonaws.com";
        credentials.port = "5432";
        DBPConnectionConfiguration config = new DBPConnectionConfiguration();
        model.saveCredentials(null, config, credentials);
        assertNull(config.getUserPassword());
        var loaded = model.loadCredentials(null, config);
        assertNull(loaded.getUserPassword());
        assertEquals("company-dev", loaded.profile);
        assertEquals("developer", loaded.getUserName());
        assertEquals("5432", loaded.port);
        assertTrue(loaded.isComplete());
    }

    @Test
    void propertyCollectionNeverExportsPassword() {
        SsoAuthModel model = new SsoAuthModel();
        SsoCredentials credentials = model.createCredentials();
        credentials.setUserName("developer");
        credentials.setUserPassword("must-not-be-exported");
        Properties properties = new Properties();
        properties.setProperty("password", "old-token");
        model.collectConnectionProperties(null, credentials, new DBPConnectionConfiguration(), properties, true);
        assertFalse(properties.containsKey("password"));
        assertEquals("developer", properties.getProperty("user"));
    }

    @Test
    void tlsDefaultsVerifyServerIdentityAndRespectExplicitSettings() {
        Properties postgres = new Properties();
        SsoAuthModel.applyTlsDefaults("org.postgresql.Driver", postgres);
        assertEquals("verify-full", postgres.getProperty("sslmode"));
        postgres.setProperty("sslmode", "verify-ca");
        SsoAuthModel.applyTlsDefaults("org.postgresql.Driver", postgres);
        assertEquals("verify-ca", postgres.getProperty("sslmode"));
        Properties mysql = new Properties();
        SsoAuthModel.applyTlsDefaults("com.mysql.cj.jdbc.Driver", mysql);
        assertEquals("VERIFY_IDENTITY", mysql.getProperty("sslMode"));
        Properties maria = new Properties();
        SsoAuthModel.applyTlsDefaults("org.mariadb.jdbc.Driver", maria);
        assertEquals("verify-full", maria.getProperty("sslMode"));
    }
}
