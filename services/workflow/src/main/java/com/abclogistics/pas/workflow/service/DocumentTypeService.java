package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.workflow.domain.DocumentTypeConfig;
import com.abclogistics.pas.workflow.dto.DocumentTypeResponse;
import com.abclogistics.pas.workflow.dto.UpdateDocumentTypeRequest;
import com.abclogistics.pas.workflow.repository.DocumentTypeConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class DocumentTypeService {

    private final DocumentTypeConfigRepository docTypeRepo;
    private final AuditRecorder audit;

    public DocumentTypeService(DocumentTypeConfigRepository docTypeRepo, AuditRecorder audit) {
        this.docTypeRepo = docTypeRepo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> list() {
        return docTypeRepo.findAll().stream()
                .sorted((a, b) -> a.getCode().compareTo(b.getCode()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentTypeResponse get(String code) {
        return toResponse(findByCode(code));
    }

    /**
     * Updates the mutable fields of a document type. {@code code} and
     * {@code numberPrefix} are immutable — numbering depends on them.
     * Serializes concurrent edits on the type row, like the activation swap.
     */
    @Transactional
    public DocumentTypeResponse update(String code, UpdateDocumentTypeRequest req) {
        DocumentTypeConfig docType = findByCode(code);
        docTypeRepo.findWithLockById(docType.getId())
                .orElseThrow(() -> new NotFoundException("Unknown document type: " + code));
        if (req.esignEnabled() && (req.esignProvider() == null || req.esignProvider().isBlank())) {
            throw new FailedPreconditionException("esign_provider is required when e-sign is enabled");
        }
        docType.setName(req.name());
        docType.setEsignEnabled(req.esignEnabled());
        docType.setEsignProvider(req.esignEnabled() ? req.esignProvider() : null);
        docTypeRepo.save(docType);
        audit.record("DOCUMENT_TYPE", docType.getId(), docType.getCode(), "workflow.document_type_updated",
                null, null, null, Map.of("name", req.name(), "esignEnabled", req.esignEnabled()));
        return toResponse(docType);
    }

    private DocumentTypeConfig findByCode(String code) {
        return docTypeRepo.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Unknown document type: " + code));
    }

    private DocumentTypeResponse toResponse(DocumentTypeConfig docType) {
        return new DocumentTypeResponse(
                docType.getId(),
                docType.getCode(),
                docType.getName(),
                docType.getNumberPrefix(),
                docType.isEsignEnabled(),
                docType.getEsignProvider());
    }
}
