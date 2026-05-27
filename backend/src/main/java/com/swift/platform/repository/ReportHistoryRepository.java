package com.swift.platform.repository;

import com.swift.platform.model.ReportHistory;
import com.swift.platform.model.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ReportHistoryRepository extends MongoRepository<ReportHistory, String> {
    Page<ReportHistory> findByCreatedByOrderByGenerationTimeDesc(String createdBy, Pageable pageable);
    Optional<ReportHistory> findByIdAndCreatedBy(String id, String createdBy);
    List<ReportHistory> findByCreatedByAndStatus(String createdBy, ReportStatus status);
    boolean existsByCreatedBy(String createdBy);
}
