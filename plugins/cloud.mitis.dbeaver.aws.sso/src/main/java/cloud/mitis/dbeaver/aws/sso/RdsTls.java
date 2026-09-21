package cloud.mitis.dbeaver.aws.sso;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

import org.eclipse.core.runtime.FileLocator;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBACertificateStorage;
import org.jkiss.dbeaver.runtime.DBWorkbench;

final class RdsTls {
    static void configure(String driverClass, String region, Properties properties, DBPDataSourceContainer container)
            throws DBException {
        String driver = driverClass.toLowerCase(Locale.ROOT);
        applyMode(driver, properties);
        if (hasCustomTrust(driver, properties)) {
            return;
        }
        try {
            URL resource = RdsTls.class.getResource("/certificates/" + partition(region) + ".pem");
            if (resource == null) {
                throw new IOException("Bundled RDS certificates are missing");
            }
            Path pem = Path.of(FileLocator.toFileURL(resource).toURI());
            applyTrust(driver, properties, pem, container,
                    driver.contains("postgresql") || driver.contains("mariadb")
                            ? null : DBWorkbench.getPlatform().getCertificateStorage());
        } catch (IOException | java.net.URISyntaxException e) {
            throw new DBException("Could not load the plugin's bundled RDS certificates. Reinstall or update the plugin.", e);
        }
    }

    static String partition(String region) {
        return region.startsWith("cn-") ? "china" : region.startsWith("us-gov-") ? "govcloud" : "commercial";
    }

    static void applyMode(String driver, Properties properties) {
        properties.putIfAbsent(driver.contains("postgresql") ? "sslmode" : "sslMode",
                driver.contains("postgresql") || driver.contains("mariadb") ? "verify-full" : "VERIFY_IDENTITY");
    }

    static boolean hasCustomTrust(String driver, Properties properties) {
        String[] keys = driver.contains("postgresql") ? new String[]{"sslrootcert", "sslfactory"}
                : driver.contains("mariadb") ? new String[]{"serverSslCert", "trustStore", "tlsSocketType"}
                : new String[]{"trustCertificateKeyStoreUrl"};
        for (String key : keys) {
            if (!properties.getProperty(key, "").isBlank()) {
                return true;
            }
        }
        return false;
    }

    static void applyTrust(String driver, Properties properties, Path pem, DBPDataSourceContainer container,
            DBACertificateStorage storage) throws IOException, DBException {
        if (hasCustomTrust(driver, properties)) {
            return;
        }
        if (driver.contains("postgresql")) {
            properties.setProperty("sslrootcert", pem.toString());
        } else if (driver.contains("mariadb")) {
            properties.setProperty("serverSslCert", pem.toString());
        } else {
            byte[] certificates = Files.readAllBytes(pem);
            String certType;
            try {
                certType = "mitis-rds-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificates));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            synchronized (storage) {
                if (!Files.exists(storage.getKeyStorePath(container, certType))) {
                    storage.addCertificate(container, certType, certificates, null, null);
                }
                properties.setProperty("trustCertificateKeyStoreUrl", storage.getKeyStorePath(container, certType).toUri().toString());
                properties.setProperty("trustCertificateKeyStoreType", storage.getKeyStoreType(container));
                properties.setProperty("trustCertificateKeyStorePassword", String.valueOf(storage.getKeyStorePassword(container, certType)));
            }
        }
    }
}
