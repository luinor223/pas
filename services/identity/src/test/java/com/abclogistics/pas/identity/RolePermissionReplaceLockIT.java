package com.abclogistics.pas.identity;

import com.abclogistics.pas.identity.dto.LoginRequest;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.RoleResponse;
import com.abclogistics.pas.identity.repository.RoleRepository;
import com.abclogistics.pas.identity.service.RoleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies db-identity.md:11 — PUT /roles/{code}/permissions locks row FOR UPDATE.
 * Two concurrent admins replacing the same role's permission set must serialize,
 * not merge into a union neither asked for.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolePermissionReplaceLockIT {

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
            privateKeyFile = Files.createTempFile("jwt-it-lock-", ".pem");
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

    @Autowired
    RoleService roleService;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    private RestClient authedClient(String userId, List<String> roles) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", userId)
                .defaultHeader("X-Roles", String.join(",", roles))
                .build();
    }

    @Test
    void concurrentReplaceSerializesViaForUpdate() throws Exception {
        // login as admin to get identity headers
        RestClient unauthed = RestClient.create("http://localhost:" + port);
        LoginResponse adminLogin = unauthed.post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(adminLogin).isNotNull();

        String targetRole = "SALES_OFFICER";
        List<String> setA = List.of("customer:read", "customer:write");
        List<String> setB = List.of("contract:read", "contract:write");

        // ensure role exists
        RoleResponse before = unauthed.get()
                .uri("/roles/" + targetRole)
                .header("X-User-Id", adminLogin.user().id().toString())
                .header("X-Roles", String.join(",", adminLogin.user().roles()))
                .retrieve().body(RoleResponse.class);
        assertThat(before).isNotNull();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<RoleResponse> fA = exec.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return roleService.replacePermissions(targetRole, setA);
        });
        Future<RoleResponse> fB = exec.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return roleService.replacePermissions(targetRole, setB);
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        RoleResponse rA = fA.get(10, TimeUnit.SECONDS);
        RoleResponse rB = fB.get(10, TimeUnit.SECONDS);
        assertThat(rA).isNotNull();
        assertThat(rB).isNotNull();

        exec.shutdown();
        assertThat(exec.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Final state must be exactly one of the two sets, never their union
        RoleResponse after = unauthed.get()
                .uri("/roles/" + targetRole)
                .header("X-User-Id", adminLogin.user().id().toString())
                .header("X-Roles", String.join(",", adminLogin.user().roles()))
                .retrieve().body(RoleResponse.class);
        assertThat(after).isNotNull();
        Set<String> finalPerms = Set.copyOf(after.permissions());
        Set<String> aSet = Set.copyOf(setA);
        Set<String> bSet = Set.copyOf(setB);
        Set<String> union = Set.of("customer:read", "customer:write", "contract:read", "contract:write");

        // Must be either A or B, and NOT the union (would indicate lost lock / merge)
        assertThat(finalPerms).isIn(aSet, bSet);
        assertThat(finalPerms).isNotEqualTo(union);
        // Also verify size is 2, not 4
        assertThat(finalPerms).hasSize(2);

        // Restore original to not pollute other tests (optional)
        // fetch original perms size before test? just leave as is; each test container is isolated per class.
    }

    @Test
    void sequentialReplaceIsTotalNotMerge() {
        RestClient unauthed = RestClient.create("http://localhost:" + port);
        LoginResponse adminLogin = unauthed.post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(LoginResponse.class);
        assertThat(adminLogin).isNotNull();

        RestClient adminClient = authedClient(adminLogin.user().id().toString(), adminLogin.user().roles());

        // first replace
        List<String> first = List.of("customer:read");
        RoleResponse afterFirst = adminClient.put().uri("/roles/LEGAL_REVIEWER/permissions")
                .body(java.util.Map.of("permissionCodes", first))
                .retrieve().body(RoleResponse.class);
        assertThat(afterFirst.permissions()).containsExactlyInAnyOrderElementsOf(first);

        // second replace with different set should be total, not union
        List<String> second = List.of("contract:read", "approval:act");
        RoleResponse afterSecond = adminClient.put().uri("/roles/LEGAL_REVIEWER/permissions")
                .body(java.util.Map.of("permissionCodes", second))
                .retrieve().body(RoleResponse.class);
        assertThat(afterSecond.permissions()).containsExactlyInAnyOrderElementsOf(second);
        assertThat(afterSecond.permissions()).doesNotContain("customer:read");
    }
}
