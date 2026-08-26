package com.abclogistics.pas.workflow.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.workflow.error.FailedPreconditionException;
import com.abclogistics.pas.workflow.domain.DocumentTypeConfig;
import com.abclogistics.pas.workflow.domain.WorkflowDefinition;
import com.abclogistics.pas.workflow.domain.WorkflowStepDefinition;
import com.abclogistics.pas.workflow.dto.CreateDefinitionRequest;
import com.abclogistics.pas.workflow.dto.UpdateStepsRequest;
import com.abclogistics.pas.workflow.dto.WorkflowDefinitionResponse;
import com.abclogistics.pas.workflow.repository.DocumentTypeConfigRepository;
import com.abclogistics.pas.workflow.repository.WorkflowDefinitionRepository;
import com.abclogistics.pas.workflow.repository.WorkflowStepDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowDefinitionService {

    private final DocumentTypeConfigRepository docTypeRepo;
    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowStepDefinitionRepository stepRepo;
    private final AuditRecorder audit;

    public WorkflowDefinitionService(DocumentTypeConfigRepository docTypeRepo,
                                     WorkflowDefinitionRepository definitionRepo,
                                     WorkflowStepDefinitionRepository stepRepo,
                                     AuditRecorder audit) {
        this.docTypeRepo = docTypeRepo;
        this.definitionRepo = definitionRepo;
        this.stepRepo = stepRepo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionResponse> list(String documentTypeCode) {
        List<WorkflowDefinition> definitions;
        if (documentTypeCode != null && !documentTypeCode.isBlank()) {
            definitions = definitionRepo.findByDocumentType_Code(documentTypeCode);
        } else {
            definitions = definitionRepo.findAll();
        }
        return definitions.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionResponse get(UUID id) {
        WorkflowDefinition def = definitionRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow definition not found: " + id));
        return toResponse(def);
    }

    @Transactional
    public WorkflowDefinitionResponse create(CreateDefinitionRequest req) {
        DocumentTypeConfig docType = docTypeRepo.findByCode(req.documentTypeCode())
                .orElseThrow(() -> new NotFoundException("Unknown document type: " + req.documentTypeCode()));
        int nextVersion = definitionRepo.maxVersionNoByDocumentTypeId(docType.getId()) + 1;
        WorkflowDefinition def = WorkflowDefinition.create(docType, nextVersion, req.name(), SecurityUtils.currentUserId());
        definitionRepo.save(def);
        audit.record("WORKFLOW_DEFINITION", def.getId(), def.getName(), "workflow.definition_created",
                null, null, null, Map.of("documentType", req.documentTypeCode(), "versionNo", nextVersion));
        return toResponse(def);
    }

    /**
     * Replaces steps of a definition. Only allowed when is_active = false.
     * Takes row lock FOR UPDATE, re-checks under lock (db-workflow.md §Key decisions).
     */
    @Transactional
    public WorkflowDefinitionResponse updateSteps(UUID id, UpdateStepsRequest req) {
        WorkflowDefinition def = definitionRepo.findWithLockById(id)
                .orElseThrow(() -> new NotFoundException("Workflow definition not found: " + id));
        if (def.isActive()) {
            throw new FailedPreconditionException("Cannot edit steps of an active definition");
        }
        // repo FOR UPDATE already guarantees serialization; re-check under lock
        stepRepo.deleteByDefinition_Id(def.getId());
        // flush delete before inserts to avoid unique violation intermediate
        stepRepo.flush();
        int order = 1;
        for (UpdateStepsRequest.StepRequest s : req.steps()) {
            WorkflowStepDefinition step = new WorkflowStepDefinition(def, order++, s.name(), s.approverRole(), s.slaHours());
            stepRepo.save(step);
        }
        audit.record("WORKFLOW_DEFINITION", def.getId(), "workflow.definition_steps_updated",
                null, Map.of("steps", req.steps().size()));
        return toResponse(def);
    }

    /**
     * Activate a definition: deactivate incumbent (0 or 1 rows) then activate target.
     * Both in one transaction with guards (seq-08). If already active, idempotent 200 with no duplicate audit.
     */
    @Transactional
    public WorkflowDefinitionResponse activate(UUID id) {
        WorkflowDefinition target = definitionRepo.findWithLockById(id)
                .orElseThrow(() -> new NotFoundException("Workflow definition not found: " + id));
        if (target.isActive()) {
            return toResponse(target);
        }
        UUID docTypeId = target.getDocumentType().getId();
        // serialize activations per document type via the document_type_config row lock (avoid predicate-lock race)
        docTypeRepo.findWithLockById(docTypeId).orElseThrow(() -> new NotFoundException("Document type not found"));
        // lock incumbent(s) — at most one due to partial unique, but lock for serialization
        List<WorkflowDefinition> actives = definitionRepo.findActiveWithLockByDocumentTypeId(docTypeId);
        for (WorkflowDefinition incumbent : actives) {
            incumbent.setActive(false);
            definitionRepo.save(incumbent);
        }
        // flush deactivation first to avoid partial unique violation on target activation (order matters)
        definitionRepo.flush();
        // activate target — must be exactly 1
        target.setActive(true);
        definitionRepo.save(target);
        definitionRepo.flush();
        // guard: ensure exactly one active after (should hold by partial unique)
        // implicit via DB constraint; if zero incumbent case, we just activated one, so ok.

        audit.record("WORKFLOW_DEFINITION", target.getId(), "workflow.definition_activated",
                null, Map.of("documentType", target.getDocumentType().getCode(), "versionNo", target.getVersionNo()));
        return toResponse(target);
    }

    private WorkflowDefinitionResponse toResponse(WorkflowDefinition def) {
        List<WorkflowStepDefinition> steps = stepRepo.findByDefinition_IdOrderByStepOrderAsc(def.getId());
        List<WorkflowDefinitionResponse.StepDefinitionDto> stepDtos = steps.stream()
                .map(s -> new WorkflowDefinitionResponse.StepDefinitionDto(s.getId(), s.getStepOrder(), s.getName(), s.getApproverRole(), s.getSlaHours()))
                .toList();
        return new WorkflowDefinitionResponse(
                def.getId(),
                def.getDocumentType().getCode(),
                def.getDocumentType().getName(),
                def.getVersionNo(),
                def.getName(),
                def.isActive(),
                def.getCreatedAt(),
                def.getCreatedBy(),
                stepDtos
        );
    }
}
