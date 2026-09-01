package com.abclogistics.pas.identity;
import com.abclogistics.pas.identity.support.Envelopes;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.dto.CreateUserRequest;
import com.abclogistics.pas.identity.dto.LoginRequest;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.UserResponse;
import com.abclogistics.pas.identity.grpc.IdentityInternalGrpcService;
import com.abclogistics.pas.identity.grpc.ListUsersByRoleRequest;
import com.abclogistics.pas.identity.grpc.ListUsersByRoleResponse;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies 00-registry.md:94 / db-identity.md:14 —
 * {@code IdentityInternal.ListUsersByRole} returns ACTIVE users only.
 * Disabled users must never be resolved as workflow step assignees or notification recipients.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListUsersByRoleActiveOnlyIT {

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
            privateKeyFile = Files.createTempFile("jwt-it-listusers-", ".pem");
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
    IdentityInternalGrpcService grpcService;

    @Autowired
    AppUserRepository users;

    private RestClient authedClient(LoginResponse login) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", login.user().id().toString())
                .defaultHeader("X-Roles", String.join(",", login.user().roles()))
                .build();
    }

    @Test
    void listUsersByRoleReturnsOnlyActive() throws Exception {
        RestClient unauthed = RestClient.create("http://localhost:" + port);
        LoginResponse adminLogin = unauthed.post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(Envelopes.LOGIN).data();
        assertThat(adminLogin).isNotNull();
        RestClient adminClient = authedClient(adminLogin);

        String roleCode = "SALES_OFFICER";
        String activeUsername = "list-active-" + System.nanoTime();
        String disabledUsername = "list-disabled-" + System.nanoTime();

        UserResponse active = adminClient.post().uri("/users")
                .body(new CreateUserRequest(activeUsername, activeUsername + "@test.local", "Password123!", "List Active", "SALES", List.of(roleCode)))
                .retrieve().body(Envelopes.USER).data();
        UserResponse disabled = adminClient.post().uri("/users")
                .body(new CreateUserRequest(disabledUsername, disabledUsername + "@test.local", "Password123!", "List Disabled", "SALES", List.of(roleCode)))
                .retrieve().body(Envelopes.USER).data();
        assertThat(active).isNotNull();
        assertThat(disabled).isNotNull();

        // Disable the second user
        adminClient.post().uri("/users/" + disabled.id() + "/disable")
                .retrieve().toBodilessEntity();

        // Verify via repository that one is DISABLED
        AppUser disabledEntity = users.findByUsername(disabledUsername).orElseThrow();
        assertThat(disabledEntity.getStatus().name()).isEqualTo("DISABLED");
        AppUser activeEntity = users.findByUsername(activeUsername).orElseThrow();
        assertThat(activeEntity.getStatus().name()).isEqualTo("ACTIVE");

        // Call gRPC service directly via StreamObserver capture
        ListUsersByRoleRequest request = ListUsersByRoleRequest.newBuilder().setRoleCode(roleCode).build();
        AtomicReference<ListUsersByRoleResponse> respRef = new AtomicReference<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        StreamObserver<ListUsersByRoleResponse> observer = new StreamObserver<>() {
            @Override public void onNext(ListUsersByRoleResponse value) { respRef.set(value); }
            @Override public void onError(Throwable t) { errRef.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        };

        grpcService.listUsersByRole(request, observer);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errRef.get()).isNull();
        ListUsersByRoleResponse response = respRef.get();
        assertThat(response).isNotNull();

        List<String> usernames = response.getUsersList().stream()
                .map(u -> u.getUsername())
                .toList();

        // Must contain active, must NOT contain disabled
        assertThat(usernames).contains(activeUsername);
        assertThat(usernames).doesNotContain(disabledUsername);

        // Also verify that disabled user's id not present
        List<String> ids = response.getUsersList().stream()
                .map(u -> u.getId())
                .toList();
        assertThat(ids).doesNotContain(disabled.id().toString());

        // Verify structure: id, username, full_name, department are populated
        response.getUsersList().forEach(u -> {
            assertThat(u.getId()).isNotBlank();
            assertThat(u.getUsername()).isNotBlank();
            assertThat(u.getFullName()).isNotBlank();
            assertThat(u.getDepartment()).isNotBlank();
        });
    }

    @Test
    void listUsersByRoleReturnsEmptyForUnknownRole() throws Exception {
        ListUsersByRoleRequest request = ListUsersByRoleRequest.newBuilder().setRoleCode("NON_EXISTENT_ROLE_" + System.nanoTime()).build();
        AtomicReference<ListUsersByRoleResponse> respRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        StreamObserver<ListUsersByRoleResponse> observer = new StreamObserver<>() {
            @Override public void onNext(ListUsersByRoleResponse value) { respRef.set(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        };
        grpcService.listUsersByRole(request, observer);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(respRef.get()).isNotNull();
        assertThat(respRef.get().getUsersList()).isEmpty();
    }

    @Test
    void listUsersByRoleExcludesDisabledEvenWhenAllUsersDisabled() throws Exception {
        // Use a role that likely has no active users after we create disabled-only case
        // Create a temporary role scenario: create user with unique role then disable
        RestClient unauthed = RestClient.create("http://localhost:" + port);
        LoginResponse adminLogin = unauthed.post().uri("/auth/login")
                .body(new LoginRequest("admin", "admin12345"))
                .retrieve().body(Envelopes.LOGIN).data();
        assertThat(adminLogin).isNotNull();
        RestClient adminClient = authedClient(adminLogin);

        String uniqueRole = "LEGAL_REVIEWER";
        String tempUser = "only-disabled-" + System.nanoTime();
        // Get current legal reviewers to know baseline
        ListUsersByRoleRequest beforeReq = ListUsersByRoleRequest.newBuilder().setRoleCode(uniqueRole).build();
        AtomicReference<ListUsersByRoleResponse> beforeRef = new AtomicReference<>();
        CountDownLatch beforeLatch = new CountDownLatch(1);
        grpcService.listUsersByRole(beforeReq, new StreamObserver<>() {
            @Override public void onNext(ListUsersByRoleResponse value) { beforeRef.set(value); }
            @Override public void onError(Throwable t) { beforeLatch.countDown(); }
            @Override public void onCompleted() { beforeLatch.countDown(); }
        });
        beforeLatch.await(5, TimeUnit.SECONDS);
        int beforeCount = beforeRef.get() != null ? beforeRef.get().getUsersCount() : 0;

        // Create a user with LEGAL_REVIEWER and immediately disable
        UserResponse u = adminClient.post().uri("/users")
                .body(new CreateUserRequest(tempUser, tempUser + "@test.local", "Password123!", "Only Disabled", "LEGAL", List.of(uniqueRole)))
                .retrieve().body(Envelopes.USER).data();
        assertThat(u).isNotNull();
        adminClient.post().uri("/users/" + u.id() + "/disable").retrieve().toBodilessEntity();

        // Now call again - count should still be beforeCount (new disabled user not included)
        AtomicReference<ListUsersByRoleResponse> afterRef = new AtomicReference<>();
        CountDownLatch afterLatch = new CountDownLatch(1);
        grpcService.listUsersByRole(beforeReq, new StreamObserver<>() {
            @Override public void onNext(ListUsersByRoleResponse value) { afterRef.set(value); }
            @Override public void onError(Throwable t) { afterLatch.countDown(); }
            @Override public void onCompleted() { afterLatch.countDown(); }
        });
        afterLatch.await(5, TimeUnit.SECONDS);
        assertThat(afterRef.get()).isNotNull();
        assertThat(afterRef.get().getUsersCount()).isEqualTo(beforeCount);
        assertThat(afterRef.get().getUsersList().stream().map(v -> v.getUsername()).toList())
                .doesNotContain(tempUser);
    }
}
