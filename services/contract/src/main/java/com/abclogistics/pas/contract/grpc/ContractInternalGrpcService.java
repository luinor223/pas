package com.abclogistics.pas.contract.grpc;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.error.FailedPreconditionException;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.storage.AttachmentStorage;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The read side other services see (D16, registry §5). Everything here is a lookup — no status
 * moves, nothing is written, and nothing calls back out.
 */
@GrpcService
public class ContractInternalGrpcService extends ContractInternalGrpc.ContractInternalImplBase {

    private final ContractService contracts;
    private final AddendumService addenda;
    private final CustomerService customers;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;

    public ContractInternalGrpcService(ContractService contracts, AddendumService addenda,
                                       CustomerService customers, AttachmentRepository attachments,
                                       AttachmentStorage storage) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.customers = customers;
        this.attachments = attachments;
        this.storage = storage;
    }

    /**
     * Billing snapshots this (PAY-03) and operations validates against it. The values are the
     * EFFECTIVE ones — an activated addendum has already rewritten valid_to / payment_term on the
     * row (registry §9²), so there is nothing for a caller to recompose.
     */
    @Override
    @Transactional(readOnly = true)
    public void getContract(GetContractRequest request,
                            StreamObserver<GetContractResponse> observer) {
        try {
            Contract contract = contracts.get(parseId(request.getId()));
            observer.onNext(GetContractResponse.newBuilder()
                    .setId(contract.getId().toString())
                    .setContractNo(contract.getContractNo())
                    .setStatus(contract.getStatus().name())
                    .setValidFrom(contract.getValidFrom().toString())
                    .setValidTo(contract.getValidTo().toString())
                    .setServiceGroup(contract.getServiceGroup().name())
                    .setVatRate(vatRate(contract))
                    .setPaymentTerm(nullToEmpty(contract.getPaymentTerm()))
                    .setCustomerId(contract.getCustomer().getId().toString())
                    .setCustomerName(contract.getCustomer().getName())
                    .setCurrency(contract.getCurrency())
                    .build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * D10 — esign-service fetches this to render the document for the provider. Guard is
     * {@code status = APPROVED} (registry §5); unlike billing's, it is not widened, because nothing
     * here flips a status before dispatching.
     */
    @Override
    @Transactional(readOnly = true)
    public void getSigningPayload(GetSigningPayloadRequest request,
                                  StreamObserver<GetSigningPayloadResponse> observer) {
        try {
            EntityType type = documentType(request.getDocumentType());
            UUID id = parseId(request.getId());
            ApprovableDocument document = type == EntityType.CONTRACT
                    ? contracts.get(id)
                    : addenda.get(id);

            if (document.getStatus() != DocumentStatus.APPROVED) {
                throw new FailedPreconditionException(
                        "%s %s is %s; a signing payload is only served for an APPROVED document "
                                + "(registry §5)".formatted(type, document.getDocumentNo(),
                                document.getStatus()));
            }
            CustomerContact signer = signerFor(document, type);

            observer.onNext(GetSigningPayloadResponse.newBuilder()
                    .setDocumentNo(document.getDocumentNo())
                    .setPdfContent(pdfContent(type, id, document.getDocumentNo()))
                    .setSignerName(signer.getFullName())
                    .setSignerEmail(nullToEmpty(signer.getEmail()))
                    .build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * The document as uploaded, not a rendering: CTR-02 already requires an attachment to submit,
     * and inventing a generated PDF here would send the provider something no one ever approved.
     * The newest upload wins — a re-upload before approval is a correction of the one before it.
     */
    private ByteString pdfContent(EntityType type, UUID documentId, String documentNo) throws IOException {
        Attachment newest = attachments.findByOwnerTypeAndOwnerId(type, documentId).stream()
                .max(Comparator.comparing(Attachment::getUploadedAt))
                .orElseThrow(() -> new FailedPreconditionException(
                        "%s %s has no attachment to sign (CTR-02 requires one at submit, so this "
                                + "document predates the rule or lost its file)"
                                .formatted(type, documentNo)));
        try (InputStream content = storage.load(newest.getStoragePath()).getInputStream()) {
            return ByteString.readFrom(content);
        }
    }

    private CustomerContact signerFor(ApprovableDocument document, EntityType type) {
        UUID customerId = type == EntityType.CONTRACT
                ? ((Contract) document).getCustomer().getId()
                : ((Addendum) document).getContract().getCustomer().getId();
        return customers.primaryContactOf(customerId)
                .orElseThrow(() -> new FailedPreconditionException(
                        "%s %s has no primary customer contact to address the signature to (D10)"
                                .formatted(type, document.getDocumentNo())));
    }

    /**
     * A null vat_rate is refused rather than sent as 0.0. proto3 cannot carry the difference, and
     * billing snapshots this field: "not yet stated" arriving as "0% VAT" is exactly the invoice
     * drift the design forbids (db-contract.md). CTR-02 makes it non-null from submit onwards, so
     * only a DRAFT can land here.
     */
    private static double vatRate(Contract contract) {
        if (contract.getVatRate() == null) {
            throw new FailedPreconditionException(
                    "Contract %s has no vatRate; it is never reported as 0 (CTR-02)"
                            .formatted(contract.getContractNo()));
        }
        return contract.getVatRate().doubleValue();
    }

    private static EntityType documentType(String documentType) {
        List<String> supported = List.of("CONTRACT", "ADDENDUM");
        if (!supported.contains(documentType)) {
            throw new IllegalArgumentException(
                    "documentType must be one of %s (got '%s')".formatted(supported, documentType));
        }
        return EntityType.valueOf(documentType);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id is not a uuid: '%s'".formatted(id));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Status mapToStatus(Exception e) {
        if (e instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT;
        if (e instanceof NotFoundException) return Status.NOT_FOUND;
        if (e instanceof FailedPreconditionException) return Status.FAILED_PRECONDITION;
        if (e instanceof UnprocessableEntityException) return Status.FAILED_PRECONDITION;
        return Status.INTERNAL;
    }
}
