package com.abclogistics.pas.identity.controller.http;

import com.abclogistics.pas.identity.dto.CreateUserRequest;
import com.abclogistics.pas.identity.dto.UpdateUserRequest;
import com.abclogistics.pas.identity.dto.UpdateUserRolesRequest;
import com.abclogistics.pas.identity.dto.UserResponse;
import com.abclogistics.pas.identity.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@PreAuthorize("hasAuthority('user:manage')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}/roles")
    public UserResponse setRoles(@PathVariable UUID id, @Valid @RequestBody UpdateUserRolesRequest request) {
        return userService.setRoles(id, request.roleCodes());
    }

    @PostMapping("/{id}/enable")
    public UserResponse enable(@PathVariable UUID id) {
        return userService.setEnabled(id, true);
    }

    @PostMapping("/{id}/disable")
    public UserResponse disable(@PathVariable UUID id) {
        return userService.setEnabled(id, false);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }
}
