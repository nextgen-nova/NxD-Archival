package com.swift.platform.service;

import com.swift.platform.model.AuditLog;
import com.swift.platform.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(String employeeId, String action, String details, String ipAddress) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .employeeId(employeeId)
                    .action(action)
                    .details(details)
                    .ipAddress(ipAddress)
                    .build());
        } catch (Exception ex) {
            log.warn("Audit log write failed: {}", ex.getMessage());
        }
    }

    public Page<AuditLog> getByEmployee(String employeeId, int page, int size) {
        return auditLogRepository.findByEmployeeId(employeeId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));
    }

    public Page<AuditLog> getAll(int page, int size) {
        return auditLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));
    }
}
