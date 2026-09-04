package com.abclogistics.pas.contract.controller.http;

import com.abclogistics.pas.common.api.ApiResponse;
import com.abclogistics.pas.contract.dto.CustomerContactResponse;
import com.abclogistics.pas.contract.dto.CustomerRequest;
import com.abclogistics.pas.contract.dto.CustomerMetricsResponse;
import com.abclogistics.pas.contract.dto.CustomerResponse;
import com.abclogistics.pas.contract.dto.SuspendRequest;
import com.abclogistics.pas.contract.dto.SnapshotPage;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.service.PageSnapshot;
import com.abclogistics.pas.contract.service.PageSnapshotCodec;
import com.abclogistics.pas.contract.service.PageableGuard;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customers;
    private final PageSnapshotCodec pageSnapshots;

    public CustomerController(CustomerService customers, PageSnapshotCodec pageSnapshots) {
        this.customers = customers;
        this.pageSnapshots = pageSnapshots;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<CustomerResponse>> list(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String cursor,
                                       @PageableDefault(size = 20) Pageable pageable) {
        PageSnapshot snapshot = pageSnapshots.resolve(cursor);
        Pageable safe = PageableGuard.sanitize(pageable, PageableGuard.CUSTOMER_SORTS);
        return SnapshotPage.of(customers.searchResponses(q, status, snapshot.createdAt(), safe), snapshot.cursor());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:read')")
    public CustomerResponse get(@PathVariable UUID id) {
        return customers.toResponse(customers.get(id));
    }

    @GetMapping("/{id}/metrics")
    @PreAuthorize("hasAuthority('customer:read') and hasAuthority('contract:read')")
    public CustomerMetricsResponse metrics(@PathVariable UUID id) {
        return customers.metrics(id);
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('customer:read')")
    public List<CustomerResponse> lookup(@RequestParam List<UUID> ids) {
        return customers.lookupResponses(ids.stream().distinct().limit(100).toList());
    }

    @GetMapping("/{id}/contacts")
    @PreAuthorize("hasAuthority('customer:read')")
    public List<CustomerContactResponse> contacts(@PathVariable UUID id) {
        customers.get(id); // 404 for an unknown customer rather than an empty list
        return customers.contactsOf(id).stream().map(CustomerContactResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customer:write')")
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        return customers.toResponse(customers.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:write')")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customers.toResponse(customers.update(id, request));
    }

    @PostMapping("/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('customer:write')")
    public void suspend(@PathVariable UUID id, @Valid @RequestBody SuspendRequest request) {
        customers.suspend(id, request.reason());
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('customer:write')")
    public void activate(@PathVariable UUID id) {
        customers.activate(id);
    }
}
