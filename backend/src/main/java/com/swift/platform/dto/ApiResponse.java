package com.swift.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String  message;
    private T       data;

    public static <T> ApiResponse<T> ok(T data)             { return new ApiResponse<>(true,  null,    data); }
    public static <T> ApiResponse<T> ok(String msg, T data) { return new ApiResponse<>(true,  msg,     data); }
    public static <T> ApiResponse<T> error(String msg)      { return new ApiResponse<>(false, msg,     null); }
}
