package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.model.AuditLog;
import com.swift.platform.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.getAll(page, size)));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getByEmployee(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.getByEmployee(employeeId, page, size)));
    }
}
