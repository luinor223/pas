package com.abclogistics.pas.pricing;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.repository.ServiceItemRepository;
import com.abclogistics.pas.pricing.service.ServiceCatalogService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** getByCode is the GetServiceItem gRPC path: found returns the item, missing is NOT_FOUND. */
class GetServiceItemNotFoundTest {

    private final ServiceItemRepository repo = mock(ServiceItemRepository.class);
    private final ServiceCatalogService catalog = new ServiceCatalogService(repo);

    @Test
    void unknownCodeThrowsNotFound() {
        when(repo.findByCode("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalog.getByCode("NOPE"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void knownCodeReturnsItem() {
        when(repo.findByCode("LIFT_ON_OFF"))
                .thenReturn(Optional.of(new ServiceItem("LIFT_ON_OFF", "Container lift on/off", "TEU")));
        assertThat(catalog.getByCode("LIFT_ON_OFF").getUnit()).isEqualTo("TEU");
    }
}
