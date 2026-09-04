package com.abclogistics.pas.billing.client;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.error.ServiceUnavailableException;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Converts internal gRPC failures into reviewed payment-statement API errors. */
final class BillingDependencyErrors {

    private BillingDependencyErrors() { }

    static RuntimeException contract(StatusRuntimeException error) {
        return switch (error.getStatus().getCode()) {
            case NOT_FOUND -> new NotFoundException(
                    "BILLING_CONTRACT_NOT_FOUND",
                    "The selected contract was not found. Choose another contract.",
                    diagnostic("Contract lookup", error));
            case INVALID_ARGUMENT -> new UnprocessableEntityException(
                    "INVALID_BILLING_CONTRACT",
                    "The selected contract is not valid for statement calculation.",
                    diagnostic("Contract lookup", error));
            case FAILED_PRECONDITION -> new FailedPreconditionException(
                    "BILLING_CONTRACT_NOT_READY",
                    "The selected contract is not ready for statement calculation.",
                    diagnostic("Contract lookup", error));
            case ABORTED, ALREADY_EXISTS -> conflict("Contract lookup", error);
            case UNAVAILABLE, DEADLINE_EXCEEDED -> unavailable("Contract service", error);
            default -> unavailable("Contract service", error);
        };
    }

    static RuntimeException operations(StatusRuntimeException error, String periodCode) {
        return switch (error.getStatus().getCode()) {
            case NOT_FOUND -> new UnprocessableEntityException(
                    "BILLING_PERIOD_NOT_FOUND",
                    "Billing period " + periodCode
                            + " does not exist. Create it in Volume Records before calculating a statement.",
                    diagnostic("Operations volume lookup", error));
            case INVALID_ARGUMENT -> new UnprocessableEntityException(
                    "INVALID_BILLING_PERIOD",
                    "Select a valid billing period in YYYY-MM format.",
                    diagnostic("Operations volume lookup", error));
            case FAILED_PRECONDITION -> new FailedPreconditionException(
                    "BILLING_PERIOD_NOT_READY",
                    "The billing period is not ready. Confirm its volume records and lock the period first.",
                    diagnostic("Operations volume lookup", error));
            case ABORTED, ALREADY_EXISTS -> conflict("Operations volume lookup", error);
            case UNAVAILABLE, DEADLINE_EXCEEDED -> unavailable("Operations service", error);
            default -> unavailable("Operations service", error);
        };
    }

    static RuntimeException pricing(StatusRuntimeException error) {
        return switch (error.getStatus().getCode()) {
            case NOT_FOUND -> new UnprocessableEntityException(
                    "EFFECTIVE_PRICE_LIST_REQUIRED",
                    "No effective price list covers the selected contract and billing period.",
                    diagnostic("Effective price lookup", error));
            case INVALID_ARGUMENT -> new UnprocessableEntityException(
                    "INVALID_PRICE_LOOKUP",
                    "The effective price list could not be resolved for the selected billing period.",
                    diagnostic("Effective price lookup", error));
            case FAILED_PRECONDITION -> new FailedPreconditionException(
                    "PRICE_LIST_NOT_READY",
                    "The matching price list is not ready for billing.",
                    diagnostic("Effective price lookup", error));
            case ABORTED, ALREADY_EXISTS -> conflict("Effective price lookup", error);
            case UNAVAILABLE, DEADLINE_EXCEEDED -> unavailable("Pricing service", error);
            default -> unavailable("Pricing service", error);
        };
    }

    private static ConflictException conflict(String operation, StatusRuntimeException error) {
        return new ConflictException(
                "BILLING_DEPENDENCY_CONFLICT",
                "Billing data changed while the statement was being calculated. Try again.",
                diagnostic(operation, error));
    }

    private static ServiceUnavailableException unavailable(String service, StatusRuntimeException error) {
        return new ServiceUnavailableException(
                "BILLING_DEPENDENCY_UNAVAILABLE",
                "A service required for statement calculation is temporarily unavailable. Try again.",
                diagnostic(service, error));
    }

    private static String diagnostic(String operation, StatusRuntimeException error) {
        Status status = error.getStatus();
        return operation + " failed with " + status.getCode() + ": " + status.getDescription();
    }
}
