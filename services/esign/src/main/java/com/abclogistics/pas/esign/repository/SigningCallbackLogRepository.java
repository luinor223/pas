package com.abclogistics.pas.esign.repository;

import com.abclogistics.pas.esign.domain.SigningCallbackLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SigningCallbackLogRepository extends JpaRepository<SigningCallbackLog, UUID> {

    Optional<SigningCallbackLog> findByProviderRef(String providerRef);

    boolean existsBySessionIdAndProviderRef(UUID sessionId, String providerRef);
}
