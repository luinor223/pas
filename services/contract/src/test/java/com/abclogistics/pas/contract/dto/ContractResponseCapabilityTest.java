package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.ChangeType;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import com.abclogistics.pas.contract.domain.TriggerKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
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

    @Test
    void contractActionCapabilitiesCombineStatusWithOrdinaryAndActiveCancellationPermissions() {
        for (List<String> permissions : List.of(
                List.<String>of(),
                List.of("contract:cancel_active"),
                List.of("contract:write"),
                List.of("contract:write", "contract:cancel_active"))) {
            authenticate(permissions);
            boolean canWrite = permissions.contains("contract:write");
            boolean canCancelActive = permissions.contains("contract:cancel_active");

            for (DocumentStatus status : DocumentStatus.values()) {
                ContractResponse response = ContractResponse.of(contract(status), true);

                assertThat(response.canEdit())
                        .as("edit: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status.isEditable());
                assertThat(response.canSubmit())
                        .as("submit: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status == DocumentStatus.DRAFT);
                assertThat(response.submitBlockedReason())
                        .as("submit reason: permissions=%s, status=%s", permissions, status)
                        .isNull();
                assertThat(response.canRevise())
                        .as("revise: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status == DocumentStatus.REJECTED);
                assertThat(response.canCancel())
                        .as("cancel: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite
                                && status.canTransitionTo(DocumentStatus.CANCELLED,
                                TriggerKind.U)
                                && (status != DocumentStatus.ACTIVE || canCancelActive));
            }
        }
    }

    @Test
    void addendumActionCapabilitiesUseAddendumWriteAndNeverContractSpecialPermission() {
        for (List<String> permissions : List.of(
                List.<String>of(),
                List.of("contract:write", "contract:cancel_active"),
                List.of("addendum:write"))) {
            authenticate(permissions);
            boolean canWrite = permissions.contains("addendum:write");

            for (DocumentStatus status : DocumentStatus.values()) {
                AddendumResponse response = AddendumResponse.of(addendum(status), true);

                assertThat(response.canEdit())
                        .as("edit: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status.isEditable());
                assertThat(response.canSubmit())
                        .as("submit: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status == DocumentStatus.DRAFT);
                assertThat(response.submitBlockedReason())
                        .as("submit reason: permissions=%s, status=%s", permissions, status)
                        .isNull();
                assertThat(response.canRevise())
                        .as("revise: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status == DocumentStatus.REJECTED);
                assertThat(response.canCancel())
                        .as("cancel: permissions=%s, status=%s", permissions, status)
                        .isEqualTo(canWrite && status.canTransitionTo(
                                DocumentStatus.CANCELLED, TriggerKind.U));
            }
        }
    }

    @Test
    void submissionCapabilityIncludesCurrentBusinessPreconditions() {
        authenticate(List.of("contract:write", "addendum:write"));
        Contract contract = contract(DocumentStatus.DRAFT);
        Addendum addendum = addendum(DocumentStatus.DRAFT);

        ContractResponse contractWithoutAttachment = ContractResponse.of(contract, false);
        assertThat(contractWithoutAttachment.canSubmit()).isFalse();
        assertThat(contractWithoutAttachment.submitBlockedReason()).contains("attachment");
        assertThat(ContractResponse.of(contract, true).canSubmit()).isTrue();
        assertThat(ContractResponse.of(contract, true).submitBlockedReason()).isNull();

        AddendumResponse addendumWithoutAttachment = AddendumResponse.of(addendum, false);
        assertThat(addendumWithoutAttachment.canSubmit()).isFalse();
        assertThat(addendumWithoutAttachment.submitBlockedReason()).contains("attachment");
        assertThat(AddendumResponse.of(addendum, true).canSubmit()).isTrue();
        assertThat(AddendumResponse.of(addendum, true).submitBlockedReason()).isNull();

        contract.setVatRate(null);
        ContractResponse invalidContract = ContractResponse.of(contract, true);
        assertThat(invalidContract.canSubmit()).isFalse();
        assertThat(invalidContract.submitBlockedReason()).contains("VAT rate");

        addendum.getContract().setStatus(DocumentStatus.CANCELLED);
        AddendumResponse invalidAddendum = AddendumResponse.of(addendum, true);
        assertThat(invalidAddendum.canSubmit()).isFalse();
        assertThat(invalidAddendum.submitBlockedReason()).contains("approved or active");
    }

    private static Contract contract(DocumentStatus status) {
        Contract contract = Contract.create("CTR-TEST", Customer.create("CUS-TEST", "Test Customer"),
                ServiceGroup.TRANSPORTATION, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        contract.setPaymentTerm("NET30");
        contract.setVatRate(BigDecimal.TEN);
        contract.setStatus(status);
        return contract;
    }

    private static Addendum addendum(DocumentStatus status) {
        Addendum addendum = Addendum.create("ADD-TEST", contract(DocumentStatus.APPROVED),
                ChangeType.TERM_EXTENSION, LocalDate.of(2026, 6, 1));
        addendum.setStatus(status);
        return addendum;
    }

    private static void authenticate(List<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null,
                        permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
