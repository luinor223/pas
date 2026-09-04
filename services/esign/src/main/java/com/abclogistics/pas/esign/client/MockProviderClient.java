package com.abclogistics.pas.esign.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class MockProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MockProviderClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final int maxAttempts;

    public MockProviderClient(@Value("${esign.provider.base-url:http://localhost:9001}") String baseUrl,
                               @Value("${esign.provider.max-attempts:3}") int maxAttempts) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Send document to the mock provider for signing.
     * Provider returns immediately with a provider_ref; actual signing happens async.
     */
    public String sendForSigning(String sessionNo, String documentNo,
                                  String signerName, String signerEmail,
                                  String callbackUrl) {
        log.info("Sending document {} to mock provider (session {})", documentNo, sessionNo);
        Map<String, Object> body = Map.of(
            "session_no", sessionNo,
            "document_no", documentNo != null ? documentNo : "",
            "signer_name", signerName != null ? signerName : "",
            "signer_email", signerEmail != null ? signerEmail : "",
            "callback_url", callbackUrl
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            baseUrl + "/sign", HttpMethod.POST, request,
            new ParameterizedTypeReference<Map<String, Object>>() {});

        if (response.getBody() == null || !response.getBody().containsKey("provider_ref")) {
            throw new RuntimeException("Mock provider did not return a provider_ref");
        }

        String providerRef = (String) response.getBody().get("provider_ref");
        log.info("Mock provider returned provider_ref={} for session {}", providerRef, sessionNo);
        return providerRef;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
