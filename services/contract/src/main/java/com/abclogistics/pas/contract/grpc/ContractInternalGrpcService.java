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

    /**
     * The statuses a signing payload is served for (registry §5). A document is only ever SENT
     * from APPROVED; ACTIVE is here because D14d activates it underneath a session already in
     * flight, and a payload refused mid-signature strands the provider with nothing to render.
     */
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
        // NOT @Transactional on the rpc methods. A lookup that throws marks the surrounding
        // transaction rollback-only, and catching it inside that transaction to build an onError
        // means the commit afterwards throws UnexpectedRollbackException — the caller gets
        // INTERNAL instead of NOT_FOUND. The template ends the transaction first, then we map.
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
            observer.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
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
     * D10 — esign-service fetches this to render the document for the provider. Guard is
     * {@code status IN (APPROVED, ACTIVE)} (registry §5).
     *
     * <p>{@code ACTIVE} is in the guard because D14d moves a contract there on its own schedule,
     * with no regard for a session in flight: sending starts from {@code APPROVED}, but the sweep
     * flips the row within one interval of the effective date, and every contract approved on or
     * after that date is {@code ACTIVE} long before the provider is done with it. An
     * {@code APPROVED}-only guard therefore refuses the payload for exactly the documents that
     * were legitimately sent — the same deadlock §5 already widened billing's guard to avoid,
     * arrived at from the other side (billing flips before dispatching; here the scheduler does).
     * The send guard is unchanged and stays {@code APPROVED}-only: this widens what can be
     * fetched, never what can be started.
     */
    @Override
    public void getSigningPayload(GetSigningPayloadRequest request,
                                  StreamObserver<GetSigningPayloadResponse> observer) {
        try {
            observer.onNext(tx.execute(s -> readSigningPayload(request)));
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(mapToStatus(e).withDescription(e.getMessage()).asRuntimeException());
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
                        "%s %s is %s; a signing payload is only served for a document that is %s "
                                + "(registry §5)".formatted(type, document.getDocumentNo(),
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
            // reading the stored file is the one checked failure here; the rest are unchecked and
            // travel out of the template on their own
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The document as uploaded, not a rendering: CTR-02 already requires an attachment to submit,
     * and inventing a generated PDF here would send the provider something no one ever approved.
     * The newest PDF wins — a re-upload before approval corrects the one before it.
     *
     * <p>Filtered by content type, not just taken newest: attachments are a general-purpose list
     * (a scanned annex, a spreadsheet of volumes), and the field on the wire is {@code pdf_content}.
     * Handing the provider the most recent upload regardless of what it is would send a customer a
     * spreadsheet to sign.
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
