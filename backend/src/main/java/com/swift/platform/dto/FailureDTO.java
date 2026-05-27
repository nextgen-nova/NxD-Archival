package com.swift.platform.dto;

import lombok.Data;

@Data
public class FailureDTO {
    private String id;
    private String messageReference;
    private String errorCode;
    private String errorMessage;
    private String stackTrace;
    private String rawInput;
    private String inputType;
    private String stage;
    private String failedAt;
}
