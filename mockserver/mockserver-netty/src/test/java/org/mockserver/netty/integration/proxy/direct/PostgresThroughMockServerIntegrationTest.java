package org.mockserver.netty.integration.proxy.direct;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.socket.PortFactory;
import org.mockserver.test.DockerAvailability;
import org.mockserver.test.TestContainerImages;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A real PostgreSQL session through MockServer forwarding a port, driven by the PostgreSQL JDBC driver. With
 * {@code sslmode=require} the driver opens in the clear with an {@code SSLRequest} and turns TLS on part way
 * through the connection; the server has {@code ssl=on} and SCRAM authentication, so it also offers channel
 * binding. MockServer keeps one upstream connection for the client connection and upgrades it to TLS when the
 * client does. Docker-gated.
 */
public class PostgresThroughMockServerIntegrationTest {

    private static final int POSTGRES_PORT = 5432;
    private static final String PASSWORD = "mockserver-test-password";
    // the server's certificate and key; the channel binding case gives MockServer the same pair to present,
    // with the authority that signed it, which MockServer requires of a fixed certificate
    private static final String SERVER_CERTIFICATE = "org/mockserver/netty/integration/tls/leaf-cert.pem";
    private static final String SERVER_PRIVATE_KEY = "org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem";
    private static final String SERVER_CERTIFICATE_AUTHORITY = "org/mockserver/netty/integration/tls/ca.pem";
    private static final String STARTUP_COMMAND = "cp /tmp/server.crt /tmp/server.key /var/lib/postgresql/"
        + " && chown postgres:postgres /var/lib/postgresql/server.crt /var/lib/postgresql/server.key"
        + " && chmod 600 /var/lib/postgresql/server.key"
        + " && exec docker-entrypoint.sh postgres -c ssl=on"
        + " -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key";

    private static GenericContainer<?> postgres;
    private ClientAndServer mockServer;

    @BeforeClass
    public static void startPostgres() throws IOException {
        Assume.assumeTrue("Docker is not available", DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable()));
        postgres = new GenericContainer<>(DockerImageName.parse(TestContainerImages.POSTGRES))
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(POSTGRES_PORT)
            .withCopyToContainer(Transferable.of(classpathBytes(SERVER_CERTIFICATE)), "/tmp/server.crt")
            .withCopyToContainer(Transferable.of(classpathBytes(SERVER_PRIVATE_KEY)), "/tmp/server.key")
            .withCommand("sh", "-c", STARTUP_COMMAND)
            // the image's set-up runs a server of its own first, so the second notice is the real one
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2).withStartupTimeout(Duration.ofSeconds(120)));
        postgres.start();
    }

    @AfterClass
    public static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @After
    public void stopMockServer() {
        stopQuietly(mockServer);
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream input = PostgresThroughMockServerIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("not on the classpath: " + path);
            }
            return input.readAllBytes();
        }
    }

    private void startMockServer(Configuration configuration) {
        mockServer = startClientAndServer(configuration, postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), PortFactory.findFreePort());
    }

    private static Connection connect(String host, int port, String applicationName, String sslMode, String channelBinding) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", PASSWORD);
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("channelBinding", channelBinding);
        properties.setProperty("ApplicationName", applicationName);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("loginTimeout", "30");
        properties.setProperty("socketTimeout", "60");
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/postgres", properties);
    }

    private Connection connectThroughMockServer(String applicationName, String sslMode, String channelBinding) throws SQLException {
        return connect("127.0.0.1", mockServer.getLocalPort(), applicationName, sslMode, channelBinding);
    }

    private static Connection connectDirectly(String applicationName, String sslMode, String channelBinding) throws SQLException {
        return connect(postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), applicationName, sslMode, channelBinding);
    }

    private static Object queryOne(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat("a row from: " + sql, resultSet.next(), is(true));
            return resultSet.getObject(1);
        }
    }

    /** A session's worth of work, then which backend served it and whether its connection to that backend is TLS. */
    private static void assertWholeSession(Connection connection, String applicationName, boolean overTls) throws SQLException {
        Object backend = queryOne(connection, "select pg_backend_pid()");

        assertThat(queryOne(connection, "select 1"), is(1));
        // past the driver's prepareThreshold of 5 the statement is named on the server, which only its own backend knows
        try (PreparedStatement statement = connection.prepareStatement("select ?::int + 1")) {
            for (int i = 0; i < 8; i++) {
                statement.setInt(1, 41 + i);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next(), is(true));
                    assertThat(resultSet.getInt(1), is(42 + i));
                }
            }
        }
        // one row larger than 64 KiB, then many small ones
        String large = (String) queryOne(connection, "select repeat('x', 200000)");
        assertThat(large.length(), is(200000));
        assertThat(large.replace("x", "").isEmpty(), is(true));
        assertThat(queryOne(connection, "select sum(i) from generate_series(1, 20000) i").toString(), is("200010000"));
        assertThat("the upstream connection is TLS", queryOne(connection, "select ssl from pg_stat_ssl where pid = pg_backend_pid()"), is(overTls));

        assertThat("the same backend throughout", queryOne(connection, "select pg_backend_pid()"), is(backend));
        try (Connection admin = connectDirectly("admin-" + UUID.randomUUID(), "disable", "disable");
             PreparedStatement statement = admin.prepareStatement("select count(*), min(pid) from pg_stat_activity where application_name = ?")) {
            statement.setString(1, applicationName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next(), is(true));
                assertThat("one backend for the one connection", resultSet.getLong(1), is(1L));
                assertThat(resultSet.getObject(2), is(backend));
            }
        }
    }

    @Test
    public void shouldReachPostgresDirectlyOverTlsWithChannelBinding() throws SQLException {
        // the harness itself: the server offers TLS and channel binding, and the certificate supports binding
        String applicationName = "direct-" + UUID.randomUUID();
        try (Connection connection = connectDirectly(applicationName, "require", "require")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    @Test
    public void shouldRunASessionInTheClearThroughMockServer() throws SQLException {
        startMockServer(configuration());
        String applicationName = "clear-" + UUID.randomUUID();
        try (Connection connection = connectThroughMockServer(applicationName, "disable", "disable")) {
            assertWholeSession(connection, applicationName, false);
        }
    }

    @Test
    public void shouldRunASessionThatUpgradesToTlsThroughMockServer() throws SQLException {
        startMockServer(configuration());
        String applicationName = "tls-" + UUID.randomUUID();
        // MockServer presents a certificate of its own, so the server's channel binding cannot be used
        try (Connection connection = connectThroughMockServer(applicationName, "require", "disable")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    @Test
    public void shouldKeepChannelBindingWhenMockServerPresentsTheServersOwnCertificate() throws SQLException {
        // the binding PostgreSQL uses is a hash of the server's certificate, which here is the one MockServer presents
        startMockServer(configuration()
            .privateKeyPath(SERVER_PRIVATE_KEY)
            .x509CertificatePath(SERVER_CERTIFICATE)
            .certificateAuthorityCertificate(SERVER_CERTIFICATE_AUTHORITY));
        String applicationName = "bound-" + UUID.randomUUID();
        try (Connection connection = connectThroughMockServer(applicationName, "require", "require")) {
            assertWholeSession(connection, applicationName, true);
        }
    }

    @Test
    public void shouldFailChannelBindingWhenMockServerPresentsACertificateOfItsOwn() {
        startMockServer(configuration());

        SQLException refused = assertThrows(SQLException.class, () -> connectThroughMockServer("unbound-" + UUID.randomUUID(), "require", "require").close());

        assertThat(refused.getMessage(), containsString("channel binding"));
    }
}
