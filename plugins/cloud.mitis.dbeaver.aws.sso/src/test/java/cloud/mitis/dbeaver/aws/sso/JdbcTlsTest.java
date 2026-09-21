package cloud.mitis.dbeaver.aws.sso;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.jkiss.dbeaver.model.impl.app.DefaultCertificateStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(20)
class JdbcTlsTest {
    @TempDir
    static Path directory;
    static Path ca;
    static SSLContext context;

    @BeforeAll
    static void createLocalCertificate() throws Exception {
        String keytool = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "keytool.exe" : "keytool";
        Path store = directory.resolve("server.p12");
        Path log = directory.resolve("keytool.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", keytool).toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "bc=ca:true",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "changeit".toCharArray());
        }
        ca = directory.resolve("test ca.pem");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getCertificate("server").getEncoded())
                + "\n-----END CERTIFICATE-----\n");
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
    }

    @ParameterizedTest
    @CsvSource({"postgresql,true,true", "mysql,true,true", "mariadb,true,true",
            "postgresql,false,true", "mysql,false,true", "mariadb,false,true",
            "postgresql,true,false", "mysql,true,false", "mariadb,true,false"})
    void actualDriversRequireTrustedCertificateAndMatchingHostname(String engine, boolean trusted, boolean matchingHost)
            throws Exception {
        String className = switch (engine) {
            case "postgresql" -> "org.postgresql.Driver";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            default -> "org.mariadb.jdbc.Driver";
        };
        Driver driver = (Driver) Class.forName(className).getConstructor().newInstance();
        Properties properties = new Properties();
        properties.setProperty("user", "test");
        properties.setProperty("password", "synthetic-token");
        properties.setProperty("connectTimeout", engine.equals("postgresql") ? "5" : "5000");
        properties.setProperty("socketTimeout", engine.equals("postgresql") ? "5" : "5000");
        RdsTls.applyMode(engine, properties);
        var storage = new DefaultCertificateStorage(null, directory.resolve(engine + trusted + matchingHost));
        RdsTls.applyTrust(engine, properties, trusted ? ca : RdsTlsTest.bundle("commercial"), RdsTlsTest.container(), storage);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            var peer = executor.submit(() -> acceptTls(server, engine));
            String url = "jdbc:" + engine + "://" + (matchingHost ? "localhost" : "127.0.0.1") + ":" + server.getLocalPort() + "/test";
            assertThrows(SQLException.class, () -> driver.connect(url, properties));
            assertEquals(trusted && matchingHost, peer.get(10, TimeUnit.SECONDS),
                    "Driver must send database protocol data only after certificate and hostname verification");
        }
    }

    private static boolean acceptTls(ServerSocket server, String engine) throws Exception {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = new DataInputStream(socket.getInputStream());
            var output = socket.getOutputStream();
            if (engine.equals("postgresql")) {
                assertEquals(8, input.readInt());
                assertEquals(80877103, input.readInt());
                output.write('S');
            } else {
                byte[] greeting = mysqlGreeting();
                output.write(new byte[]{(byte) greeting.length, 0, 0, 0});
                output.write(greeting);
                output.flush();
                int length = input.readUnsignedByte() | input.readUnsignedByte() << 8 | input.readUnsignedByte() << 16;
                input.readUnsignedByte();
                assertEquals(32, length);
                assertEquals(length, input.readNBytes(length).length);
            }
            output.flush();
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true)) {
                tls.setUseClientMode(false);
                tls.startHandshake();
                return tls.getInputStream().read() != -1;
            } catch (java.io.IOException e) {
                return false;
            }
        }
    }

    private static byte[] mysqlGreeting() throws Exception {
        var packet = new ByteArrayOutputStream();
        packet.write(10);
        packet.write("8.0.0-test\0".getBytes(StandardCharsets.UTF_8));
        packet.write(new byte[]{1, 0, 0, 0});
        packet.write("12345678\0".getBytes(StandardCharsets.UTF_8));
        packet.write(new byte[]{1, (byte) 0x8a, 33, 2, 0, 8, 0, 21});
        packet.write(new byte[10]);
        packet.write("abcdefghijkl\0mysql_native_password\0".getBytes(StandardCharsets.UTF_8));
        return packet.toByteArray();
    }
}
