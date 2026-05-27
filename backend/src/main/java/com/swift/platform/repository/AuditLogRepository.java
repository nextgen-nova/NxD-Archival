package com.swift.platform.repository;

import com.swift.platform.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {
    Page<AuditLog> findByEmployeeId(String employeeId, Pageable pageable);
    Page<AuditLog> findByAction(String action, Pageable pageable);
}
