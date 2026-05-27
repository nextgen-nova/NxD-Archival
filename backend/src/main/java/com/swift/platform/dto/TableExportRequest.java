package com.swift.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableExportRequest {
    private Map<String, String> filters = new LinkedHashMap<>();
    private List<ExportColumnRequest> columns = new ArrayList<>();
}
