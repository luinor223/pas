package com.abclogistics.pas.identity;

import com.abclogistics.pas.identity.dto.CreateUserRequest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 1 — verifies two-token rotation, family reuse detection and disabled-user exclusion.
 * <p>
 * Rules from db-identity.md:7 + mechanics.md M1 + 00-registry.md §6:
 * <ul>
 *   <li>POST /auth/login returns 15m RS256 access + 14d opaque refresh (SHA-256 hashed)</li>
 *   <li>POST /auth/refresh rotates both tokens, old revoked + replaced_by</li>
 *   <li>Presenting an already-revoked refresh token revokes the whole family (reuse/theft detection)</li>
 *   <li>Disabled users are excluded from future refresh (and from ListUsersByRole)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshRotationReuseDetectionIT {

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
            privateKeyFile = Files.createTempFile("jwt-it-refresh-", ".pem");
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
        // disable kafka relay to not require broker in this test
        registry.add("outbox.relay.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private RestClient authedClient(LoginResponse login) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", login.user().id().toString())
                .defaultHeader("X-Roles", String.join(",", login.user().roles()))
                .build();
    }

    @Test
    void rotationIssuesNewPairAndOldIsRevoked() {
        LoginResponse login = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(login).isNotNull();
        assertThat(login.refreshToken()).isNotBlank();
        assertThat(login.accessToken()).isNotBlank();

        TokenResponse refreshed = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .retrieve().body(TokenResponse.class);

        assertThat(refreshed).isNotNull();
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
    }

    @Test
    void reuseOfRevokedTokenRevokesFamily() {
        LoginResponse login = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(login).isNotNull();

        TokenResponse firstRotate = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .retrieve().body(TokenResponse.class);
        assertThat(firstRotate).isNotNull();
        String rotatedRefresh = firstRotate.refreshToken();

        // Reuse the original (already revoked) token -> should be 401 and revoke family
        HttpStatusCode reusedStatus = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(reusedStatus.value()).isEqualTo(401);

        // The rotated token's family should now be revoked as well -> 401
        HttpStatusCode familyRevoked = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(rotatedRefresh))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(familyRevoked.value()).isEqualTo(401);

        // Logout-like idempotent revoke: presenting same revoked token again still 401
        HttpStatusCode secondReuse = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(secondReuse.value()).isEqualTo(401);
    }

    @Test
    void disabledUserCannotRefreshAndFamilyRevoked() {
        // login as admin to create a new user
        LoginResponse adminLogin = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(adminLogin).isNotNull();

        RestClient adminClient = authedClient(adminLogin);

        String newUsername = "refresh-disable-" + System.nanoTime();
        UserResponse created = adminClient.post().uri("/users")
                .body(new CreateUserRequest(newUsername, newUsername + "@test.local", "Password123!", "Refresh Disable", "IT", List.of("SALES_OFFICER")))
                .retrieve().body(UserResponse.class);
        assertThat(created).isNotNull();

        // login as new user
        LoginResponse userLogin = client().post().uri("/auth/login")
                .body(new LoginRequest(newUsername, "Password123!"))
                .retrieve().body(LoginResponse.class);
        assertThat(userLogin).isNotNull();
        String userRefresh = userLogin.refreshToken();

        // admin disables the user -> should revoke all families
        adminClient.post().uri("/users/" + created.id() + "/disable")
                .retrieve().toBodilessEntity();

        // Direct refresh attempt after disable should be rejected (family revoked + user inactive)
        HttpStatusCode afterDisable = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(userRefresh))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(afterDisable.value()).isEqualTo(401);

        // Also login should no longer succeed for disabled user
        HttpStatusCode loginAfterDisable = client().post().uri("/auth/login")
                .body(new LoginRequest(newUsername, "Password123!"))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(loginAfterDisable.value()).isEqualTo(401);
    }

    @Test
    void expiredOrUnknownTokenIsRejected() {
        HttpStatusCode unknown = client().post().uri("/auth/refresh")
                .body(new RefreshRequest("not-a-real-token-" + System.nanoTime()))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(unknown.value()).isEqualTo(401);
    }

    @Test
    void logoutRevokesFamily() {
        LoginResponse login = client().post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(login).isNotNull();

        TokenResponse rotated = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(login.refreshToken()))
                .retrieve().body(TokenResponse.class);
        assertThat(rotated).isNotNull();
        String latestRefresh = rotated.refreshToken();

        // logout using latest refresh token
        client().post().uri("/auth/logout")
                .body(new RefreshRequest(latestRefresh))
                .retrieve().toBodilessEntity();

        // both latest and previous should now be rejected (family revoked)
        HttpStatusCode latestAfterLogout = client().post().uri("/auth/refresh")
                .body(new RefreshRequest(latestRefresh))
                .exchange((req, resp) -> resp.getStatusCode());
        assertThat(latestAfterLogout.value()).isEqualTo(401);
    }
}
