package com.swift.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    private Integer page;
    private Integer size;
    private String cursor;
    private Boolean countExact;
    private Map<String, String> filters = new LinkedHashMap<>();
}
