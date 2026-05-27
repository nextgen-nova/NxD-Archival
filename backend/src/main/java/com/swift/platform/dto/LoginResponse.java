package com.swift.platform.dto;
import lombok.*;
@Data @AllArgsConstructor
public class LoginResponse {
    private String token;
    private String employeeId;
    private String name;
    private String role;
    private String email;
    private long   expiresIn;
}
