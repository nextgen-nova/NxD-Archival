package com.swift.platform.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "#{@appConfig.usersCollection}")
public class User {
    @Id private String id;
    @Indexed(unique = true) private String employeeId;
    private String password;
    @Indexed private String role;
    private String name;
    @Indexed(unique = true) private String email;
    @Indexed @Builder.Default private boolean active = true;
    private Object createdAt;
    private Instant lastLogin;
    private Instant updatedAt;
}
