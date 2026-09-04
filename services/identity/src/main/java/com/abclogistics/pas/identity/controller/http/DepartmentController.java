package com.abclogistics.pas.identity.controller.http;

import com.abclogistics.pas.identity.dto.DepartmentResponse;
import com.abclogistics.pas.identity.repository.DepartmentRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/departments")
@PreAuthorize("hasAuthority('user:manage')")
public class DepartmentController {

    private final DepartmentRepository departments;

    public DepartmentController(DepartmentRepository departments) {
        this.departments = departments;
    }

    @GetMapping
    public List<DepartmentResponse> list() {
        return departments.findAll().stream()
                .map(DepartmentResponse::from)
                .toList();
    }
}
