package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractResponseCapabilityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void addendumCreationCapabilityCombinesEveryParentStatusWithBothPermissions() {
        for (List<String> permissions : List.of(
                List.<String>of(),
                List.of("contract:read"),
                List.of("addendum:write"),
                List.of("contract:read", "addendum:write"))) {
            authenticate(permissions);
            for (DocumentStatus status : DocumentStatus.values()) {
                Contract contract = contract(status);
                boolean expected = permissions.containsAll(List.of("contract:read", "addendum:write"))
                        && (status == DocumentStatus.APPROVED || status == DocumentStatus.ACTIVE);

                assertThat(ContractResponse.of(contract).canCreateAddendum())
                        .as("permissions=%s, status=%s", permissions, status)
                        .isEqualTo(expected);
            }
        }
    }

    private static Contract contract(DocumentStatus status) {
        Contract contract = Contract.create("CTR-TEST", Customer.create("CUS-TEST", "Test Customer"),
                ServiceGroup.TRANSPORTATION, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        contract.setStatus(status);
        return contract;
    }

    private static void authenticate(List<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null,
                        permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
