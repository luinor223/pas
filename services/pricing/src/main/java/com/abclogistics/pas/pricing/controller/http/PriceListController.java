package com.abclogistics.pas.pricing.controller.http;

import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.dto.PriceListDtos.CreatePriceListRequest;
import com.abclogistics.pas.pricing.dto.PriceListDtos.CreateVersionRequest;
import com.abclogistics.pas.pricing.dto.PriceListDtos.LineDto;
import com.abclogistics.pas.pricing.dto.PriceListDtos.PriceListResponse;
import com.abclogistics.pas.pricing.dto.PriceListDtos.PriceListPageResponse;
import com.abclogistics.pas.pricing.dto.PriceListDtos.ReplaceLinesRequest;
import com.abclogistics.pas.pricing.dto.PriceListDtos.VersionDetailResponse;
import com.abclogistics.pas.pricing.dto.PriceListDtos.VersionResponse;
import com.abclogistics.pas.pricing.service.PriceListService;
import com.abclogistics.pas.pricing.service.PriceListService.LineInput;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/price-lists")
public class PriceListController {

    private final PriceListService lists;
    private final PriceListVersionService versions;

    public PriceListController(PriceListService lists, PriceListVersionService versions) {
        this.lists = lists;
        this.versions = versions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('pricelist:read')")
    public PriceListPageResponse search(@RequestParam(required = false) UUID customerId,
                                          @RequestParam(required = false) UUID contractId,
                                          @RequestParam(required = false) String serviceGroup,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "15") int size) {
        var result = lists.searchPage(customerId, contractId, serviceGroup, q,
                Math.max(0, page), Math.max(1, Math.min(size, 100)));
        return new PriceListPageResponse(
                result.getContent().stream().map(PriceListResponse::of).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pricelist:write')")
    public PriceListResponse create(@RequestBody CreatePriceListRequest req) {
        PriceList list = lists.create(req.customerId(), req.contractId(), req.serviceGroup(), req.note());
        return PriceListResponse.of(list);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('pricelist:read')")
    public PriceListResponse get(@PathVariable UUID id) {
        return PriceListResponse.of(lists.get(id));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('pricelist:write')")
    public VersionResponse addVersion(@PathVariable UUID id, @Valid @RequestBody CreateVersionRequest req) {
        PriceListVersion v = lists.addVersion(id, req.validFrom(), req.validTo(), req.addendumId());
        return VersionResponse.of(v);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('pricelist:read')")
    public List<VersionResponse> listVersions(@PathVariable UUID id) {
        lists.get(id); // Keep a missing parent distinct from a valid list with no versions.
        return lists.versionsOf(id).stream().map(VersionResponse::of).toList();
    }

    @GetMapping("/versions/{versionId}")
    @PreAuthorize("hasAuthority('pricelist:read')")
    public VersionDetailResponse getVersionById(@PathVariable UUID versionId) {
        return detail(versionId);
    }

    @GetMapping("/{id}/versions/{versionId}")
    @PreAuthorize("hasAuthority('pricelist:read')")
    public VersionDetailResponse getVersion(@PathVariable UUID id, @PathVariable UUID versionId) {
        return detail(id, versionId);
    }

    @PutMapping("/{id}/versions/{versionId}/lines")
    @PreAuthorize("hasAuthority('pricelist:write')")
    public VersionDetailResponse replaceLines(@PathVariable UUID id, @PathVariable UUID versionId,
                                              @Valid @RequestBody ReplaceLinesRequest req) {
        lists.getVersion(id, versionId);
        List<LineInput> inputs = req.lines().stream()
                .map((LineDto l) -> new LineInput(l.serviceCode(), l.unitPrice())).toList();
        lists.replaceLines(versionId, inputs);
        return detail(id, versionId);
    }

    private VersionDetailResponse detail(UUID versionId) {
        return new VersionDetailResponse(VersionResponse.of(lists.getVersion(versionId)), lists.lineViews(versionId));
    }

    private VersionDetailResponse detail(UUID priceListId, UUID versionId) {
        return new VersionDetailResponse(VersionResponse.of(lists.getVersion(priceListId, versionId)),
                lists.lineViews(versionId));
    }

    @PostMapping("/{id}/versions/{versionId}/submit")
    @PreAuthorize("hasAuthority('pricelist:write')")
    public VersionResponse submit(@PathVariable UUID id, @PathVariable UUID versionId) {
        lists.getVersion(id, versionId);
        versions.submit(versionId);
        return VersionResponse.of(lists.getVersion(versionId));
    }

    @PostMapping("/{id}/versions/{versionId}/revise")
    @PreAuthorize("hasAuthority('pricelist:write')")
    public VersionResponse revise(@PathVariable UUID id, @PathVariable UUID versionId) {
        lists.getVersion(id, versionId);
        versions.revise(versionId);
        return VersionResponse.of(lists.getVersion(versionId));
    }
}
