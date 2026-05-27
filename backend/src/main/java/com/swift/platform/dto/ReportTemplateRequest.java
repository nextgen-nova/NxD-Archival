package com.swift.platform.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ReportTemplateRequest {
    private String criteriaName;
    private String format;
    private String profile;
    private Map<String, Object> criteria;
}
