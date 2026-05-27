package com.swift.platform.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDTO {
    private String  id;
    private String  employeeId;
    private String  name;
    private String  email;
    private String  role;
    private boolean active;
    private Object  createdAt;
    private Instant lastLogin;
    private Instant updatedAt;
    private String  password; // write-only
}
