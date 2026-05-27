package com.swift.platform.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class ReportHistoryResponse {
    private String id;
    private String fileName;
    private String status;
    private Instant generationTime;
    private String format;
    private Map<String, Object> criteria;
    private boolean downloadReady;
    private String downloadUrl;
    private String errorMessage;
}
