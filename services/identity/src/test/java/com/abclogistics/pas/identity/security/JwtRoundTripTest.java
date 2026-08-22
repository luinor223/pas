package com.abclogistics.pas.identity.security;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Department;
import com.abclogistics.pas.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtRoundTripTest {

    private final KeyPair keyPair = rsaKeyPair();
    private final JwtIssuer issuer = new JwtIssuer(
            (RSAPrivateKey) keyPair.getPrivate(), "pas-identity", Duration.ofMinutes(15));

    @Test
    void issuesTokenVerifiableWithThePublicKey() {
        String token = issuer.issue(sampleUser()).token();

        Claims claims = Jwts.parser()
                .verifyWith((RSAPublicKey) keyPair.getPublic())
                .requireIssuer("pas-identity")
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isNotBlank();
        assertThat(claims.get("username", String.class)).isEqualTo("admin");
        assertThat(claims.get("full_name", String.class)).isEqualTo("System Admin");
        assertThat(claims.get("department", String.class)).isEqualTo("IT");
        assertThat(claims.get("roles", List.class)).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void rejectsTokenVerifiedWithAnotherKey() {
        String token = issuer.issue(sampleUser()).token();
        RSAPublicKey foreignKey = (RSAPublicKey) rsaKeyPair().getPublic();

        assertThatThrownBy(() -> Jwts.parser()
                .verifyWith(foreignKey)
                .build()
                .parseSignedClaims(token))
                .isInstanceOf(SignatureException.class);
    }

    private AppUser sampleUser() {
        Department it = instantiate(Department.class);
        ReflectionTestUtils.setField(it, "code", "IT");

        Role admin = instantiate(Role.class);
        ReflectionTestUtils.setField(admin, "code", "SYSTEM_ADMIN");

        AppUser user = AppUser.create("admin", "admin@abclogistics.local", "hash", "System Admin", it);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.getRoles().add(admin);
        return user;
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
