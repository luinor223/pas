package com.abclogistics.pas.esign.controller.http;

import com.abclogistics.pas.common.api.ApiResponse;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.dto.CreateSigningSessionRequest;
import com.abclogistics.pas.esign.dto.SigningSessionResponse;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/signing-sessions")
public class SigningSessionController {

    private final SigningSessionService sessionService;

    public SigningSessionController(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('esign:send')")
    public ResponseEntity<ApiResponse<SigningSessionResponse>> createSession(
            @RequestBody CreateSigningSessionRequest request) {
        AuthenticatedUser user = SecurityUtils.currentUser()
            .orElseThrow(() -> new IllegalStateException("No authenticated user"));
        UUID idempotencyKey = UUID.randomUUID();
        SigningSession session = sessionService.createSession(
            request.documentType(), request.documentId(), request.documentNo(),
            request.customerName(), request.signerName(), request.signerEmail(),
            idempotencyKey, user.userId(), user.fullName());
        SigningSessionResponse response = toResponse(session);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('esign:send')")
    public ResponseEntity<ApiResponse<Page<SigningSessionResponse>>> listSessions(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        Page<SigningSessionResponse> page = sessionService.listSessions(status, pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('esign:send')")
    public ResponseEntity<ApiResponse<SigningSessionResponse>> getSession(@PathVariable UUID id) {
        SigningSessionResponse session = sessionService.getSession(id);
        return ResponseEntity.ok(ApiResponse.of(session));
    }

    @GetMapping("/by-session-no/{sessionNo}")
    public ResponseEntity<ApiResponse<SigningSessionResponse>> getSessionBySessionNo(
            @PathVariable String sessionNo) {
        SigningSessionResponse session = sessionService.getSessionBySessionNo(sessionNo);
        return ResponseEntity.ok(ApiResponse.of(session));
    }

    @GetMapping("/by-document/{documentType}/{documentId}")
    @PreAuthorize("hasAuthority('esign:send')")
    public ResponseEntity<ApiResponse<List<SigningSessionResponse>>> getSessionsByDocument(
            @PathVariable String documentType,
            @PathVariable UUID documentId) {
        List<SigningSessionResponse> sessions = sessionService.getSessionsByDocument(documentType, documentId);
        return ResponseEntity.ok(ApiResponse.of(sessions));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('esign:cancel')")
    public ResponseEntity<ApiResponse<SigningSessionResponse>> cancelSession(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        AuthenticatedUser user = SecurityUtils.currentUser()
            .orElseThrow(() -> new IllegalStateException("No authenticated user"));
        String reason = body != null ? body.get("reason") : null;
        sessionService.cancelSession(id, user.userId(), user.fullName(), reason);
        SigningSessionResponse session = sessionService.getSession(id);
        return ResponseEntity.ok(ApiResponse.of(session));
    }

    private SigningSessionResponse toResponse(SigningSession s) {
        return new SigningSessionResponse(
            s.getId(), s.getSessionNo(), s.getDocumentTypeCode(), s.getDocumentId(),
            s.getDocumentNo(), s.getCustomerName(), s.getSignerName(), s.getSignerEmail(),
            s.getProvider(), s.getProviderRef(), s.getStatus().name(), s.getAttempts(),
            s.getLastError(), s.getRequestedByName(), s.getSentAt(), s.getCompletedAt(), s.getCreatedAt()
        );
    }
}
