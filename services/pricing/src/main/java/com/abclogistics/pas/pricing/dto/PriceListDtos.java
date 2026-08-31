package com.abclogistics.pas.pricing.dto;

import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response records for the price-list REST surface. */
public final class PriceListDtos {

    private PriceListDtos() {}

    public record CreatePriceListRequest(UUID customerId, UUID contractId, String serviceGroup, String note) {}

    public record PriceListResponse(UUID id, String priceListNo, UUID customerId, UUID contractId,
                                    String serviceGroup, String scopeKey, String note) {
        public static PriceListResponse of(PriceList l) {
            return new PriceListResponse(l.getId(), l.getPriceListNo(), l.getCustomerId(), l.getContractId(),
                    l.getServiceGroup(), l.getScopeKey(), l.getNote());
        }
    }

    public record CreateVersionRequest(@NotNull LocalDate validFrom, @NotNull LocalDate validTo, UUID addendumId) {}

    public record VersionResponse(UUID id, UUID priceListId, int versionNo, String status,
                                  LocalDate validFrom, LocalDate validTo, UUID addendumId) {
        public static VersionResponse of(PriceListVersion v) {
            return new VersionResponse(v.getId(), v.getPriceListId(), v.getVersionNo(), v.getStatus().name(),
                    v.getValidFrom(), v.getValidTo(), v.getAddendumId());
        }
    }

    public record LineDto(@NotNull String serviceCode, @NotNull @PositiveOrZero BigDecimal unitPrice) {}

    public record ReplaceLinesRequest(@NotNull List<LineDto> lines) {}

    public record VersionDetailResponse(VersionResponse version, List<PriceLineView> lines) {}
}
