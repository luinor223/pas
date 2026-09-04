package com.abclogistics.pas.esignmock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@RestController
public class MockSignController {

    private static final Logger log = LoggerFactory.getLogger(MockSignController.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Accept a sign request. Returns immediately with a provider_ref.
     * After a delay, POSTs the result back to the callback URL.
     */
    @PostMapping("/sign")
    public ResponseEntity<Map<String, Object>> sign(@RequestBody Map<String, Object> request) {
        String sessionNo = (String) request.getOrDefault("session_no", "unknown");
        String documentNo = (String) request.getOrDefault("document_no", "");
        String signerName = (String) request.getOrDefault("signer_name", "");
        String callbackUrl = (String) request.getOrDefault("callback_url", "");

        String providerRef = "MOCK-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("MockSign: Received sign request for session={}, doc={}, signer={}",
            sessionNo, documentNo, signerName);
        log.info("MockSign: provider_ref={}, callback={}", providerRef, callbackUrl);

        // Simulate async signing: callback after 5-15 seconds
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            long delaySeconds = 5 + (long) (Math.random() * 10);
            String finalProviderRef = providerRef;
            scheduler.schedule(() -> sendCallback(callbackUrl, sessionNo, finalProviderRef, "SIGNED"),
                delaySeconds, TimeUnit.SECONDS);
            log.info("MockSign: Scheduled callback in {}s for session {}", delaySeconds, sessionNo);
        }

        return ResponseEntity.accepted().body(Map.of(
            "provider_ref", providerRef,
            "status", "accepted"
        ));
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "esign-mock-provider"));
    }

    private void sendCallback(String callbackUrl, String sessionNo, String providerRef, String result) {
        try {
            log.info("MockSign: Sending callback to {} for session {} with result {}",
                callbackUrl, sessionNo, result);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(
                Map.of("provider_ref", providerRef, "result", result), headers);

            restTemplate.postForEntity(callbackUrl, entity, Map.class);
            log.info("MockSign: Callback sent successfully for session {}", sessionNo);
        } catch (Exception e) {
            log.error("MockSign: Failed to send callback for session {}: {}", sessionNo, e.getMessage());
        }
    }
}
