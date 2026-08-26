package com.abclogistics.pas.workflow.controller;

import com.abclogistics.pas.workflow.dto.CreateDefinitionRequest;
import com.abclogistics.pas.workflow.dto.UpdateStepsRequest;
import com.abclogistics.pas.workflow.dto.WorkflowDefinitionResponse;
import com.abclogistics.pas.workflow.service.WorkflowDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workflow-definitions")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkflowDefinitionResponse> list(@RequestParam(required = false) String documentTypeCode) {
        return service.list(documentTypeCode);
    }

    @GetMapping("/{id}")
    public WorkflowDefinitionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('workflow:configure')")
    public WorkflowDefinitionResponse create(@Valid @RequestBody CreateDefinitionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}/steps")
    @PreAuthorize("hasAuthority('workflow:configure')")
    public WorkflowDefinitionResponse updateSteps(@PathVariable UUID id, @Valid @RequestBody UpdateStepsRequest request) {
        return service.updateSteps(id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('workflow:configure')")
    public WorkflowDefinitionResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}
