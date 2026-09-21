package cloud.mitis.dbeaver.aws.sso;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Properties;

import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.impl.app.DefaultCertificateStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RdsTlsTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"eu-west-1,commercial,108", "cn-north-1,china,6", "us-gov-west-1,govcloud,6"})
    void packagedBundlesContainOnlyValidSelfSignedRoots(String region, String partition, int count) throws Exception {
        assertEquals(partition, RdsTls.partition(region));
        try (var input = Files.newInputStream(bundle(partition))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            assertEquals(count, certificates.size());
            for (var certificate : certificates) {
                var x509 = (X509Certificate) certificate;
                assertTrue(x509.getBasicConstraints() >= 0);
                assertEquals(x509.getSubjectX500Principal(), x509.getIssuerX500Principal());
                x509.verify(x509.getPublicKey());
                x509.checkValidity();
            }
        }
    }

    @Test
    void mysqlTrustStoreContainsEveryRootAndIsReused() throws Exception {
        var storage = new DefaultCertificateStorage(null, directory.resolve("stores"));
        Properties properties = new Properties();
        RdsTls.applyTrust("mysql", properties, bundle("commercial"), container(), storage);
        Path store = Path.of(java.net.URI.create(properties.getProperty("trustCertificateKeyStoreUrl")));
        byte[] original = Files.readAllBytes(store);
        KeyStore keyStore = KeyStore.getInstance(properties.getProperty("trustCertificateKeyStoreType"));
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, properties.getProperty("trustCertificateKeyStorePassword").toCharArray());
        }
        assertEquals(108, keyStore.size());
        RdsTls.applyTrust("mysql", new Properties(), bundle("commercial"), container(), storage);
        assertArrayEquals(original, Files.readAllBytes(store));
        Properties china = new Properties();
        RdsTls.applyTrust("mysql", china, bundle("china"), container(), storage);
        assertNotEquals(properties.getProperty("trustCertificateKeyStoreUrl"), china.getProperty("trustCertificateKeyStoreUrl"));
    }

    @ParameterizedTest
    @CsvSource({"postgresql,sslrootcert", "postgresql,sslfactory", "mysql,trustCertificateKeyStoreUrl",
            "mariadb,serverSslCert", "mariadb,trustStore", "mariadb,tlsSocketType"})
    void explicitTrustConfigurationIsPreserved(String driver, String key) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(key, "user-supplied-setting");
        var original = new Properties();
        original.putAll(properties);
        RdsTls.applyTrust(driver, properties, directory.resolve("does-not-exist"), null, null);
        assertEquals(original, properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mariadb"})
    void pemLocationSupportsSpaces(String driver) throws Exception {
        Path pem = directory.resolve("path with spaces.pem");
        Files.copy(bundle("commercial"), pem);
        Properties properties = new Properties();
        RdsTls.applyTrust(driver, properties, pem, null, null);
        String key = driver.equals("postgresql") ? "sslrootcert" : "serverSslCert";
        assertEquals(pem, Path.of(properties.getProperty(key)));
    }

    static Path bundle(String partition) throws Exception {
        return Path.of(RdsTlsTest.class.getResource("/certificates/" + partition + ".pem").toURI());
    }

    static DBPDataSourceContainer container() {
        return (DBPDataSourceContainer) Proxy.newProxyInstance(DBPDataSourceContainer.class.getClassLoader(),
                new Class<?>[]{DBPDataSourceContainer.class}, (proxy, method, args) ->
                        method.getName().equals("getId") ? "test-connection" : null);
    }
}
