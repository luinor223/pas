package com.abclogistics.pas.pricing;

import com.abclogistics.pas.pricing.domain.PriceList;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** scope_key encodes the lookup precedence CONTRACT > CUSTOMER+GROUP > CUSTOMER > GROUP (PRC-01). */
class ScopeKeyDerivationTest {

    private final UUID cust = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private final UUID ctr = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    void contractOnly() {
        assertThat(PriceList.deriveScopeKey(null, ctr, null)).isEqualTo("CONTRACT:" + ctr);
    }

    @Test
    void customerPlusGroup() {
        assertThat(PriceList.deriveScopeKey(cust, null, "WAREHOUSING"))
                .isEqualTo("CUSTOMER:" + cust + ":GROUP:WAREHOUSING");
    }

    @Test
    void customerOnlyThenGroupOnly() {
        assertThat(PriceList.deriveScopeKey(cust, null, null)).isEqualTo("CUSTOMER:" + cust);
        assertThat(PriceList.deriveScopeKey(cust, null, "  ")).isEqualTo("CUSTOMER:" + cust);
        assertThat(PriceList.deriveScopeKey(null, null, "TRANSPORTATION")).isEqualTo("GROUP:TRANSPORTATION");
    }

    @Test
    void noScopeIsRejected() {
        assertThatThrownBy(() -> PriceList.deriveScopeKey(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contractCannotBeCombinedWithAnotherScope() {
        assertThatThrownBy(() -> PriceList.deriveScopeKey(cust, ctr, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceList.deriveScopeKey(null, ctr, "STEVEDORING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceList.deriveScopeKey(cust, ctr, "STEVEDORING"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
