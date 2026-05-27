package com.swift.platform.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ReportHistoryPageResponse {
    private List<ReportHistoryResponse> content;
    private long totalElements;
    private int totalPages;
    private int pageNumber;
    private int pageSize;
    private boolean first;
    private boolean last;
    private long generatedCount;
    private long inProgressCount;
    private long failedCount;
}
