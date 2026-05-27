package com.swift.platform.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "#{@appConfig.auditCollection}")
public class AuditLog {
    @Id private String id;
    @Indexed private String employeeId;
    @Indexed private String action;     // LOGIN, SEARCH, USER_CREATE, USER_UPDATE, etc.
    private String details;
    private String ipAddress;
    @Indexed @Builder.Default private Instant timestamp = Instant.now();
}
