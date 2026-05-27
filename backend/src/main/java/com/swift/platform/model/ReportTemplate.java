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
@Document(collection = "#{@appConfig.reportTemplatesCollection}")
public class ReportTemplate {
    @Id
    private String id;

    @Indexed
    private String createdBy;

    private String criteriaName;
    private String lastModifier;
    private Instant creationDate;
    private String profile;
    private String format;
    private Map<String, Object> criteria;
}
