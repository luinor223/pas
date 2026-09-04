package com.abclogistics.pas.contract.controller.grpc;

import com.abclogistics.pas.common.error.GrpcStatusMapper;
import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.contract.grpc.*;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.service.AddendumService;
import com.abclogistics.pas.contract.service.ContractService;
import com.abclogistics.pas.contract.service.CustomerService;
import com.abclogistics.pas.contract.storage.AttachmentStorage;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The read side other services see (D16, registry §5). Everything here is a lookup — no status
 * moves, nothing is written, and nothing calls back out.
 */
@GrpcService
public class ContractInternalGrpcService extends ContractInternalGrpc.ContractInternalImplBase {

    /** registry §5. ACTIVE is included because D14d activates under a session already in flight. */
    private static final Set<DocumentStatus> SIGNABLE =
            EnumSet.of(DocumentStatus.APPROVED, DocumentStatus.ACTIVE);

    private final ContractService contracts;
    private final AddendumService addenda;
    private final CustomerService customers;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TransactionTemplate tx;

    public ContractInternalGrpcService(ContractService contracts, AddendumService addenda,
                                       CustomerService customers, AttachmentRepository attachments,
                                       AttachmentStorage storage,
                                       PlatformTransactionManager transactionManager) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.customers = customers;
        this.attachments = attachments;
        this.storage = storage;
        // not @Transactional: catching inside the transaction to build an onError makes the
        // commit throw UnexpectedRollbackException, so the caller gets INTERNAL, not NOT_FOUND
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    /**
     * Billing snapshots this (PAY-03) and operations validates against it. The values are the
     * EFFECTIVE ones — an activated addendum has already rewritten valid_to / payment_term on the
     * row (registry §9²), so there is nothing for a caller to recompose.
     */
    @Override
    public void getContract(GetContractRequest request,
                            StreamObserver<GetContractResponse> observer) {
        try {
            observer.onNext(tx.execute(s -> readContract(request)));
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(GrpcStatusMapper.toStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private GetContractResponse readContract(GetContractRequest request) {
        Contract contract = contracts.get(parseId(request.getId()));
        return GetContractResponse.newBuilder()
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
                .build();
    }

    /**
     * D10 — esign fetches this to render the document. Guard {@link #SIGNABLE}: widened to ACTIVE
     * because an APPROVED-only guard refused the payload for documents legitimately sent, once
     * D14d activated them mid-signature (registry §5 change log). Sending still starts from
     * APPROVED only.
     */
    @Override
    public void getSigningPayload(GetSigningPayloadRequest request,
                                  StreamObserver<GetSigningPayloadResponse> observer) {
        try {
            observer.onNext(tx.execute(s -> readSigningPayload(request)));
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(GrpcStatusMapper.toStatus(e).withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private GetSigningPayloadResponse readSigningPayload(GetSigningPayloadRequest request) {
        try {
            EntityType type = documentType(request.getDocumentType());
            UUID id = parseId(request.getId());
            ApprovableDocument document = type == EntityType.CONTRACT
                    ? contracts.get(id)
                    : addenda.get(id);

            if (!SIGNABLE.contains(document.getStatus())) {
                throw new FailedPreconditionException(
                        "%s %s is %s; a signing payload is only served for a document that is %s"
                                .formatted(type, document.getDocumentNo(),
                                document.getStatus(), SIGNABLE));
            }
            CustomerContact signer = signerFor(document, type);

            return GetSigningPayloadResponse.newBuilder()
                    .setDocumentNo(document.getDocumentNo())
                    .setPdfContent(pdfContent(type, id, document.getDocumentNo()))
                    .setSignerName(signer.getFullName())
                    .setSignerEmail(nullToEmpty(signer.getEmail()))
                    .build();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The newest PDF as uploaded, never a rendering invented here — nobody approved a generated
     * one. Filtered by type because attachments are a general-purpose list and the wire field is
     * {@code pdf_content}: the newest upload could be a spreadsheet.
     */
    private ByteString pdfContent(EntityType type, UUID documentId, String documentNo) throws IOException {
        List<Attachment> all = attachments.findByOwnerTypeAndOwnerId(type, documentId);
        Attachment newest = all.stream()
                .filter(ContractInternalGrpcService::isPdf)
                .max(Comparator.comparing(Attachment::getUploadedAt))
                .orElseThrow(() -> new FailedPreconditionException(all.isEmpty()
                        ? "%s %s has no attachment to sign (CTR-02 requires one at submit, so this "
                                .formatted(type, documentNo)
                                + "document predates the rule or lost its file)"
                        : "%s %s has %d attachment(s) but none is a PDF; there is nothing here to "
                                .formatted(type, documentNo, all.size())
                                + "send for signature (D10)"));
        try (InputStream content = storage.load(newest.getStoragePath()).getInputStream()) {
            return ByteString.readFrom(content);
        }
    }

    /** Content type first; a missing one falls back to the extension rather than being refused. */
    private static boolean isPdf(Attachment attachment) {
        String contentType = attachment.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            return contentType.toLowerCase(Locale.ROOT).startsWith("application/pdf");
        }
        String name = attachment.getFileName();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf");
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
     * Refused rather than sent as 0.0: proto3 cannot carry the difference and billing snapshots
     * this, so "not yet stated" would become "0% VAT" on an invoice (db-contract.md).
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

}
