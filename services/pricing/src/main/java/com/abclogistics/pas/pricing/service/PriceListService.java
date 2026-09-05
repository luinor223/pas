package com.abclogistics.pas.pricing.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.pricing.domain.PriceLine;
import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.repository.PriceLineRepository;
import com.abclogistics.pas.pricing.repository.PriceListRepository;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import com.abclogistics.pas.pricing.repository.ServiceItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** Price list + version + line editing (DRAFT-only edits, PRC-05). */
@Service
public class PriceListService {

    private final PriceListRepository lists;
    private final PriceListVersionRepository versions;
    private final PriceLineRepository lines;
    private final ServiceItemRepository items;
    private final AuditRecorder audit;

    public PriceListService(PriceListRepository lists, PriceListVersionRepository versions,
                            PriceLineRepository lines, ServiceItemRepository items, AuditRecorder audit) {
        this.lists = lists;
        this.versions = versions;
        this.lines = lines;
        this.items = items;
        this.audit = audit;
    }

    @Transactional
    public PriceList create(UUID customerId, UUID contractId, String serviceGroup, String note) {
        String normalizedGroup = serviceGroup == null || serviceGroup.isBlank() ? null : serviceGroup.trim();
        PriceList.validateScope(customerId, contractId, normalizedGroup);
        String no = "PRC-%04d".formatted(lists.nextPriceListNo());
        PriceList list = lists.save(new PriceList(no, customerId, contractId, normalizedGroup, note));
        audit.record("PRICE_LIST", list.getId(), list.getPriceListNo(), "CREATE",
                null, null, note, Map.of());
        return list;
    }

    @Transactional(readOnly = true)
    public Page<PriceList> searchPage(UUID customerId, UUID contractId, String serviceGroup,
                                      String q, int page, int size) {
        String searchTerm = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String group = serviceGroup == null || serviceGroup.isBlank() ? null : serviceGroup;
        return lists.searchPage(customerId, contractId, group, searchTerm, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public PriceList get(UUID id) {
        return lists.findById(id).orElseThrow(() -> new NotFoundException("No price list " + id));
    }

    @Transactional(readOnly = true)
    public List<PriceListVersion> versionsOf(UUID priceListId) {
        return versions.findByPriceListIdOrderByVersionNo(priceListId);
    }

    @Transactional(readOnly = true)
    public PriceListVersion getVersion(UUID versionId) {
        return versions.findById(versionId).orElseThrow(() -> new NotFoundException("No price list version " + versionId));
    }

    @Transactional(readOnly = true)
    public PriceListVersion getVersion(UUID priceListId, UUID versionId) {
        PriceListVersion version = getVersion(versionId);
        if (!version.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("No price list version " + versionId + " for price list " + priceListId);
        }
        return version;
    }

    @Transactional(readOnly = true)
    public List<PriceLine> linesOf(UUID versionId) {
        return lines.findByVersionId(versionId);
    }

    @Transactional(readOnly = true)
    public List<com.abclogistics.pas.pricing.dto.PriceLineView> lineViews(UUID versionId) {
        return lines.viewsByVersion(versionId);
    }

    /** Adds a DRAFT version. addendumId (D8) is provenance only; validFrom is taken as given (the UI
     *  pre-fills it from the addendum's effective_from). scope_key is copied from the parent list. */
    @Transactional
    public PriceListVersion addVersion(UUID priceListId, LocalDate validFrom, LocalDate validTo, UUID addendumId) {
        PriceList list = get(priceListId);
        if (validFrom == null || validTo == null || validFrom.isAfter(validTo)) {
            throw new IllegalArgumentException("valid_from must be on or before valid_to (PRC-02)");
        }
        int nextNo = versions.maxVersionNo(priceListId) + 1;
        PriceListVersion version = versions.save(new PriceListVersion(
                priceListId, nextNo, list.getScopeKey(), validFrom, validTo, addendumId));
        audit.record("PRICE_LIST_VERSION", version.getId(), versionEntityNo(list, nextNo), "CREATE",
                null, PriceListVersionStatus.DRAFT.name(), null,
                Map.<String, Object>of("versionNo", nextNo));
        return version;
    }

    /** Updates the validity window of a DRAFT version (PRC-05: no edits past DRAFT). The
     *  overlap guard runs at submit/approve, so a draft window change is safe here. */
    @Transactional
    public PriceListVersion updateVersionDates(UUID versionId, LocalDate validFrom, LocalDate validTo) {
        PriceListVersion version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        if (version.getStatus() != PriceListVersionStatus.DRAFT) {
            throw new ConflictException("A " + version.getStatus() + " version is read-only; create a new version (PRC-05)");
        }
        if (validFrom == null || validTo == null || validFrom.isAfter(validTo)) {
            throw new IllegalArgumentException("valid_from must be on or before valid_to (PRC-02)");
        }
        version.setValidFrom(validFrom);
        version.setValidTo(validTo);
        PriceList list = get(version.getPriceListId());
        audit.record("PRICE_LIST_VERSION", versionId, versionEntityNo(list, version.getVersionNo()),
                "EDIT_DATES", null, null, null,
                Map.<String, Object>of("validFrom", validFrom.toString(), "validTo", validTo.toString()));
        return version;
    }

    /** Replaces the lines of a DRAFT version (PRC-05: no edits past DRAFT). */
    @Transactional
    public void replaceLines(UUID versionId, List<LineInput> inputs) {
        PriceListVersion version = versions.findByIdForUpdate(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        if (version.getStatus() != PriceListVersionStatus.DRAFT) {
            throw new ConflictException("A " + version.getStatus() + " version is read-only; create a new version (PRC-05)");
        }
        lines.deleteByVersionId(versionId);
        Map<String, ServiceItem> itemsByCode = items.findByCodeIn(
                        inputs.stream().map(LineInput::serviceCode).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ServiceItem::getCode, Function.identity()));
        for (LineInput in : inputs) {
            ServiceItem item = itemsByCode.get(in.serviceCode());
            if (item == null) {
                throw new NotFoundException("No service item with code " + in.serviceCode());
            }
            lines.save(new PriceLine(versionId, item.getId(), in.unitPrice()));
        }
        PriceList list = get(version.getPriceListId());
        audit.record("PRICE_LIST_VERSION", versionId, versionEntityNo(list, version.getVersionNo()),
                "EDIT_LINES", null, null, null,
                Map.<String, Object>of("lineCount", inputs.size()));
    }

    public record LineInput(String serviceCode, BigDecimal unitPrice) {}

    private static String versionEntityNo(PriceList list, int versionNo) {
        return list.getPriceListNo() + " v" + versionNo;
    }
}
