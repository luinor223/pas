package com.abclogistics.pas.billing.client;

import com.abclogistics.pas.common.error.DomainException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.error.ServiceUnavailableException;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingDependencyErrorsTest {

    @Test
    void missingPeriodBecomesActionableBusinessError() {
        DomainException error = (DomainException) BillingDependencyErrors.operations(
                Status.NOT_FOUND.withDescription("Period not found: 2026-06").asRuntimeException(),
                "2026-06");

        assertThat(error).isInstanceOf(UnprocessableEntityException.class);
        assertThat(error.getPublicCode()).isEqualTo("BILLING_PERIOD_NOT_FOUND");
        assertThat(error.getPublicMessage())
                .isEqualTo("Billing period 2026-06 does not exist. "
                        + "Create it in Volume Records before calculating a statement.");
        assertThat(error.getPublicMessage()).doesNotContain("NOT_FOUND", "grpc");
    }

    @Test
    void missingEffectivePriceListExplainsThePrerequisite() {
        DomainException error = (DomainException) BillingDependencyErrors.pricing(
                Status.NOT_FOUND.withDescription("No effective price list for scope").asRuntimeException());

        assertThat(error).isInstanceOf(UnprocessableEntityException.class);
        assertThat(error.getPublicCode()).isEqualTo("EFFECTIVE_PRICE_LIST_REQUIRED");
        assertThat(error.getPublicMessage())
                .isEqualTo("No effective price list covers the selected contract and billing period.");
    }

    @Test
    void missingContractAndUnavailableDependenciesKeepDistinctResponses() {
        DomainException missing = (DomainException) BillingDependencyErrors.contract(
                Status.NOT_FOUND.withDescription("Contract UUID not found").asRuntimeException());
        DomainException unavailable = (DomainException) BillingDependencyErrors.operations(
                Status.DEADLINE_EXCEEDED.withDescription("deadline").asRuntimeException(), "2026-06");

        assertThat(missing).isInstanceOf(NotFoundException.class);
        assertThat(missing.getPublicCode()).isEqualTo("BILLING_CONTRACT_NOT_FOUND");
        assertThat(unavailable).isInstanceOf(ServiceUnavailableException.class);
        assertThat(unavailable.getPublicCode()).isEqualTo("BILLING_DEPENDENCY_UNAVAILABLE");
    }
}
