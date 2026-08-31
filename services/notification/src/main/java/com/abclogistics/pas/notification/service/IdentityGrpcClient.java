package com.abclogistics.pas.notification.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** {@code IdentityInternal.ListUsersByRole} — ACTIVE users only (registry §5). */
@Component
public class IdentityGrpcClient {

    /** 2s deadline (read, §5.1). {@code UNAVAILABLE} is the only retryable status. */
    public List<UUID> listUsersByRole(String roleCode) {
        throw new UnsupportedOperationException("Phase B: call IdentityInternal.ListUsersByRole");
    }
}
