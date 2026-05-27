package com.swift.platform.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class ReportTemplateResponse {
    private String id;
    private String criteriaName;
    private String createdBy;
    private String lastModifier;
    private Instant creationDate;
    private String profile;
    private String format;
    private Map<String, Object> criteria;
}
