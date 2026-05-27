package com.swift.platform.repository;

import com.swift.platform.model.ReportTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ReportTemplateRepository extends MongoRepository<ReportTemplate, String> {
    List<ReportTemplate> findByCreatedByOrderByCreationDateDesc(String createdBy);
    Optional<ReportTemplate> findByIdAndCreatedBy(String id, String createdBy);
    boolean existsByCreatedBy(String createdBy);
}
