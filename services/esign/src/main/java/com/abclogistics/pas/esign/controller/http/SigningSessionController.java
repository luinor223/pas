package com.abclogistics.pas.esign.controller.http;

import com.abclogistics.pas.common.api.ApiResponse;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
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
    @PreAuthorize("hasAuthority('esign:send')")
    public ResponseEntity<ApiResponse<SigningSessionResponse>> getSessionBySessionNo(
            @PathVariable String sessionNo) {
        SigningSessionResponse session = sessionService.getSessionBySessionNo(sessionNo);
        return ResponseEntity.ok(ApiResponse.of(session));
    }

    @GetMapping("/by-document/{documentType}/{documentId}")
    @PreAuthorize("hasAuthority('esign:send')"
        + " or (#documentType == 'CONTRACT' and hasAuthority('contract:read'))"
        + " or (#documentType == 'ADDENDUM' and hasAuthority('addendum:read'))"
        + " or (#documentType == 'PAYMENT_STATEMENT' and hasAuthority('statement:read'))")
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
}
