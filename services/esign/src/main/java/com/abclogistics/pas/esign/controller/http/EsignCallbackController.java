package com.abclogistics.pas.esign.controller.http;

import com.abclogistics.pas.esign.dto.CallbackRequest;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/callbacks/esign")
public class EsignCallbackController {

    private static final Logger log = LoggerFactory.getLogger(EsignCallbackController.class);

    private final SigningSessionService sessionService;

    public EsignCallbackController(SigningSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Webhook endpoint for the esign provider (D16 single REST exception).
     * Provider POSTs here with {provider_ref, result} after signing completes.
     * The session_no is in the URL path for pre-provider_ref correlation.
     */
    @PostMapping("/{sessionNo}")
    public ResponseEntity<Map<String, String>> handleCallback(
            @PathVariable String sessionNo,
            @RequestBody CallbackRequest request) {
        log.info("Received callback for session_no={}, provider_ref={}, result={}",
            sessionNo, request.providerRef(), request.result());

        try {
            sessionService.handleCallback(sessionNo, request.providerRef(),
                request.result(), request.error());
        } catch (Exception e) {
            log.error("Error processing callback for session {}: {}", sessionNo, e.getMessage(), e);
        }

        // Always return 200 to provider (idempotent)
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
