package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.error.UnprocessableEntityException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSnapshotCodecTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:06:07.123456789Z");
    private static final String SECRET = "pagination-test-secret-32-characters-long";

    @Test
    void signedVersionedCursorRoundTripsWithoutLosingTimestampPrecision() {
        PageSnapshot first = codecAt(NOW).resolve(null);
        PageSnapshot next = codecAt(NOW.plusSeconds(30)).resolve(first.cursor());

        assertThat(first.cursor()).startsWith("v1.");
        assertThat(next.createdAt()).isEqualTo(NOW);
        assertThat(next.cursor()).isEqualTo(first.cursor());
    }

    @Test
    void sharedReplicaSecretAcceptsCursorAndDifferentSecretRejectsIt() {
        String cursor = codecAt(NOW).resolve(null).cursor();
        PageSnapshotCodec sameSecretReplica = new PageSnapshotCodec(
                SECRET, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        PageSnapshotCodec differentSecretReplica = new PageSnapshotCodec(
                "different-pagination-secret-32-characters", Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        assertThat(sameSecretReplica.resolve(cursor).createdAt()).isEqualTo(NOW);
        assertInvalid(cursor, differentSecretReplica);
    }

    @Test
    void tamperedAndForgedCursorsFailIntegrityValidation() {
        String valid = codecAt(NOW).resolve(null).cursor();
        String[] parts = valid.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A");
        String forgedFuturePayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                NOW.plusSeconds(30).toString().getBytes(StandardCharsets.UTF_8));

        assertInvalid(parts[0] + "." + tamperedPayload + "." + parts[2], codecAt(NOW));
        assertInvalid("v1." + forgedFuturePayload + "." + parts[2], codecAt(NOW));
        assertInvalid("not-a-cursor", codecAt(NOW));
    }

    @Test
    void validlySignedExpiredAndFutureCursorsStillFailSafely() {
        String expired = codecAt(NOW.minusSeconds(3_601)).resolve(null).cursor();
        String future = codecAt(NOW.plusSeconds(61)).resolve(null).cursor();

        assertInvalid(expired, codecAt(NOW));
        assertInvalid(future, codecAt(NOW));
    }

    private static PageSnapshotCodec codecAt(Instant instant) {
        return new PageSnapshotCodec(SECRET, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static void assertInvalid(String cursor, PageSnapshotCodec codec) {
        assertThatThrownBy(() -> codec.resolve(cursor))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("The page cursor is invalid or has expired; return to the first page.");
    }
}
