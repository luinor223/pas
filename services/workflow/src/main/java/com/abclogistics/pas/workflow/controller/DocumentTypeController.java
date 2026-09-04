package com.abclogistics.pas.workflow.controller;

import com.abclogistics.pas.workflow.dto.DocumentTypeResponse;
import com.abclogistics.pas.workflow.dto.UpdateDocumentTypeRequest;
import com.abclogistics.pas.workflow.service.DocumentTypeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/document-types")
public class DocumentTypeController {

    private final DocumentTypeService service;

    public DocumentTypeController(DocumentTypeService service) {
        this.service = service;
    }

    @GetMapping
    public List<DocumentTypeResponse> list() {
        return service.list();
    }

    @GetMapping("/{code}")
    public DocumentTypeResponse get(@PathVariable String code) {
        return service.get(code);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('doctype:configure')")
    public DocumentTypeResponse update(@PathVariable String code,
                                       @Valid @RequestBody UpdateDocumentTypeRequest request) {
        return service.update(code, request);
    }
}
