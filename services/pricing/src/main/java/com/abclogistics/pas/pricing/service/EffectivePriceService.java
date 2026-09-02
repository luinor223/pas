package com.abclogistics.pas.pricing.service;

import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import com.abclogistics.pas.pricing.dto.PriceLineView;
import com.abclogistics.pas.pricing.repository.PriceLineRepository;
import com.abclogistics.pas.pricing.repository.PriceListRepository;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Historical effective-price resolution for billing (registry §5). Precedence CONTRACT > CUSTOMER+
 * GROUP > CUSTOMER: the most specific scope with a version whose validity contains {@code date}
 * wins. The lookup is historical — SUPERSEDED/EXPIRED versions still resolve for a past date, so a
 * statement rebuilt after the fact reprices identically.
 */
@Service
public class EffectivePriceService {

    private static final List<PriceListVersionStatus> EVER_EFFECTIVE = List.of(
            PriceListVersionStatus.APPROVED, PriceListVersionStatus.EFFECTIVE,
            PriceListVersionStatus.SUPERSEDED, PriceListVersionStatus.EXPIRED);

    private final PriceListVersionRepository versions;
    private final PriceListRepository lists;
    private final PriceLineRepository lines;

    public EffectivePriceService(PriceListVersionRepository versions, PriceListRepository lists,
                                 PriceLineRepository lines) {
        this.versions = versions;
        this.lists = lists;
        this.lines = lines;
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedPriceList> resolve(UUID contractId, UUID customerId, String serviceGroup, LocalDate date) {
        for (String scopeKey : PriceList.scopeKeyCandidates(customerId, contractId, serviceGroup)) {
            List<PriceListVersion> match = versions.effectiveAt(scopeKey, EVER_EFFECTIVE, date, Limit.of(1));
            if (!match.isEmpty()) {
                PriceListVersion v = match.get(0);   // latest valid_from whose range holds date
                PriceList list = lists.findById(v.getPriceListId()).orElseThrow();
                return Optional.of(new ResolvedPriceList(v.getId(), list.getPriceListNo(), v.getVersionNo(),
                        v.getValidFrom(), v.getValidTo(), lines.viewsByVersion(v.getId())));
            }
        }
        return Optional.empty();
    }

    public record ResolvedPriceList(UUID versionId, String priceListNo, int versionNo,
                                    LocalDate validFrom, LocalDate validTo,
                                    List<PriceLineView> lines) {}
}
