package com.swift.platform.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data public class LoginRequest {
    @NotBlank private String employeeId;
    @NotBlank private String password;
    private String loginMode; // EMPLOYEE | ADMIN
}
