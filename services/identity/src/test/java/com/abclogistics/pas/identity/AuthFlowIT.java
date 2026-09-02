package com.abclogistics.pas.identity;
import com.abclogistics.pas.identity.support.Envelopes;

import com.abclogistics.pas.identity.dto.LoginRequest;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.RefreshRequest;
import com.abclogistics.pas.identity.dto.TokenResponse;
import com.abclogistics.pas.identity.dto.UserResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static Path privateKeyFile;

    static {
        try {
            var pair = KeyPairGenerator.getInstance("RSA");
            pair.initialize(2048);
            byte[] pkcs8 = pair.generateKeyPair().getPrivate().getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                    + "\n-----END PRIVATE KEY-----\n";
            privateKeyFile = Files.createTempFile("jwt-it-", ".pem");
            Files.writeString(privateKeyFile, pem);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("jwt.private-key-path", () -> privateKeyFile.toString());
        registry.add("outbox.relay.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void adminLogsInThenListsUsersWithEdgeHeaders() {
        LoginResponse login = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve()
                .body(Envelopes.LOGIN).data();

        assertThat(login).isNotNull();
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshToken()).isNotBlank();
        assertThat(login.user().roles()).contains("SYSTEM_ADMIN");

        // simulate the identity headers the edge injects after validating the JWT
        UserResponse[] users = client().get().uri("/users")
                .header("X-User-Id", login.user().id().toString())
                .header("X-Roles", String.join(",", login.user().roles()))
                .retrieve()
                .body(Envelopes.USER_ARRAY).data();

        assertThat(users).isNotNull();
        assertThat(users).anyMatch(u -> u.username().equals("admin"));
    }

    @Test
    void rotatesRefreshTokenAndDetectsReuse() {
        LoginResponse login = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve()
                .body(Envelopes.LOGIN).data();

        assertThat(login).isNotNull();
        assertThat(login.refreshToken()).isNotBlank();

        TokenResponse refreshed = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .retrieve()
                .body(Envelopes.TOKEN).data();

        assertThat(refreshed).isNotNull();
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());

        HttpStatusCode reused = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(reused.value()).isEqualTo(401);

        HttpStatusCode familyRevoked = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(refreshed.refreshToken()))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(familyRevoked.value()).isEqualTo(401);
    }

    @Test
    void rejectsUnauthenticatedUserList() {
        HttpStatusCode status = client().get().uri("/users")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isIn(401, 403);
    }
}
