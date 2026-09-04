package com.abclogistics.pas.workflow.controller.http;

import com.abclogistics.pas.workflow.dto.StepActionRequest;
import com.abclogistics.pas.workflow.service.WorkflowInstanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WorkflowStepActionController {

    private final WorkflowInstanceService instanceService;

    public WorkflowStepActionController(WorkflowInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @PostMapping("/workflow-steps/{stepInstanceId}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('approval:act')")
    public void act(@PathVariable UUID stepInstanceId, @Valid @RequestBody StepActionRequest request) {
        instanceService.actOnStep(stepInstanceId, request.action(), request.comment(), request.idempotencyKey());
    }
}
