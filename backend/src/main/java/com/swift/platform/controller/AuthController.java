package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.dto.LoginRequest;
import com.swift.platform.dto.LoginResponse;
import com.swift.platform.service.AuthService;
import com.swift.platform.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService  authService;
    private final AuditService auditService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpReq) {
        try {
            LoginResponse response = authService.login(request);
            auditService.log(request.getEmployeeId(), "LOGIN",
                    "Login mode: " + request.getLoginMode(), httpReq.getRemoteAddr());
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            return ResponseEntity.status(401).body(ApiResponse.error(ex.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String employeeId = (String) request.getAttribute("employeeId");
        String role       = (String) request.getAttribute("role");
        String name       = (String) request.getAttribute("name");
        String email      = (String) request.getAttribute("email");
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "employeeId", employeeId != null ? employeeId : "",
                "role",       role       != null ? role       : "",
                "name",       name       != null ? name       : "",
                "email",      email      != null ? email      : ""
        )));
    }
}
