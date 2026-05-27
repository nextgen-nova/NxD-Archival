package com.swift.platform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "#{@appConfig.reportHistoryCollection}")
public class ReportHistory {
    @Id
    private String id;

    @Indexed
    private String createdBy;

    @Indexed
    private Instant generationTime;

    @Indexed
    private ReportStatus status;

    private String fileName;
    private String format;
    private Map<String, Object> criteria;
    private String contentType;
    private String contentBase64;
    private String errorMessage;
}
