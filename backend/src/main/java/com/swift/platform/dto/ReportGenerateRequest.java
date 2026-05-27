package com.swift.platform.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ReportGenerateRequest {
    private String format;
    private Map<String, Object> criteria;
}
