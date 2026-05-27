package com.swift.platform.controller;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.DropdownOptionsResponse;
import com.swift.platform.dto.ExportColumnRequest;
import com.swift.platform.dto.FieldConfigResponse;
import com.swift.platform.dto.PagedResponse;
import com.swift.platform.dto.SearchRequest;
import com.swift.platform.dto.SearchResponse;
import com.swift.platform.dto.TableExportRequest;
import com.swift.platform.service.AuditService;
import com.swift.platform.service.FieldConfigService;
import com.swift.platform.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SearchController {

    private final SearchService     searchService;
    private final AuditService      auditService;
    private final AppConfig         appConfig;
    private final FieldConfigService fieldConfigService;

    @GetMapping("/api/search")
    public PagedResponse<SearchResponse> search(@RequestParam Map<String, String> allParams,
                                                HttpServletRequest httpReq) {
        int page = parseIntOr(allParams.remove("page"), 0);
        int size = parseIntOr(allParams.remove("size"), appConfig.getDefaultPageSize());

        Map<String, String> filters = new HashMap<>();
        allParams.forEach((k, v) -> { if (v != null && !v.isBlank()) filters.put(k, v); });

        String employeeId = (String) httpReq.getAttribute("employeeId");
        auditService.log(employeeId, "SEARCH", "Filters: " + filters, httpReq.getRemoteAddr());

        return searchService.search(filters, page, size);
    }

    @PostMapping("/api/search")
    public PagedResponse<SearchResponse> search(@RequestBody(required = false) SearchRequest request,
                                                HttpServletRequest httpReq) {
        int page = request != null && request.getPage() != null ? request.getPage() : 0;
        int size = request != null && request.getSize() != null ? request.getSize() : appConfig.getDefaultPageSize();
        String cursor = request != null ? request.getCursor() : null;
        Boolean countExact = request != null ? request.getCountExact() : null;
        Map<String, String> filters = normalizeFilters(request == null ? null : request.getFilters());

        String employeeId = (String) httpReq.getAttribute("employeeId");
        auditService.log(employeeId, "SEARCH", "Filters: " + filters, httpReq.getRemoteAddr());

        return searchService.search(filters, page, size, cursor, countExact);
    }

    @GetMapping("/api/search/export-all")
    public List<SearchResponse> exportAll(@RequestParam Map<String, String> allParams,
                                          HttpServletRequest httpReq) {
        allParams.remove("page");
        allParams.remove("size");

        Map<String, String> filters = new HashMap<>();
        allParams.forEach((k, v) -> { if (v != null && !v.isBlank()) filters.put(k, v); });

        String employeeId = (String) httpReq.getAttribute("employeeId");
        auditService.log(employeeId, "EXPORT_ALL", "Filters: " + filters, httpReq.getRemoteAddr());

        return searchService.searchAllForExport(filters);
    }

    @PostMapping("/api/search/export-all")
    public List<SearchResponse> exportAll(@RequestBody(required = false) SearchRequest request,
                                          HttpServletRequest httpReq) {
        Map<String, String> filters = normalizeFilters(request == null ? null : request.getFilters());

        String employeeId = (String) httpReq.getAttribute("employeeId");
        auditService.log(employeeId, "EXPORT_ALL", "Filters: " + filters, httpReq.getRemoteAddr());

        return searchService.searchAllForExport(filters);
    }

    @PostMapping("/api/search/export-all/file")
    public ResponseEntity<StreamingResponseBody> exportAllFile(@RequestParam String format,
                                                               @RequestBody(required = false) TableExportRequest request,
                                                               HttpServletRequest httpReq) {
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase();
        if (!List.of("csv", "excel").contains(normalizedFormat)) {
            throw new IllegalArgumentException("Unsupported export format: " + format);
        }

        Map<String, String> filters = normalizeFilters(request == null ? null : request.getFilters());
        List<ExportColumnRequest> columns = normalizeColumns(request == null ? null : request.getColumns());

        String employeeId = (String) httpReq.getAttribute("employeeId");
        auditService.log(employeeId, "EXPORT_ALL_FILE", "Format: " + normalizedFormat + ", Filters: " + filters, httpReq.getRemoteAddr());

        String fileName = "swift_messages_result_table_all." + ("excel".equals(normalizedFormat) ? "xlsx" : "csv");
        MediaType mediaType = "excel".equals(normalizedFormat)
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv;charset=UTF-8");

        StreamingResponseBody body = outputStream ->
                searchService.streamResultTableExport(filters, columns, normalizedFormat, outputStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(mediaType)
                .body(body);
    }

    @GetMapping("/api/search/detail/by-reference/{reference}")
    public SearchResponse messageDetail(@PathVariable String reference) {
        return searchService.getMessageDetail(reference);
    }

    @PostMapping("/api/search/details/by-references")
    public List<SearchResponse> messageDetails(@RequestBody List<String> references) {
        return searchService.getMessageDetailsByReferences(references);
    }

    @GetMapping("/api/dropdown-options")
    public DropdownOptionsResponse dropdownOptions() {
        return searchService.getDropdownOptions();
    }

    /** Legacy alias kept for backwards compatibility */
    @GetMapping("/api/search/options")
    public DropdownOptionsResponse dropdownOptionsLegacy() {
        return searchService.getDropdownOptions();
    }

    /**
     * Dynamic field config — scans the DB and returns all searchable fields.
     * Frontend uses this to build the Advanced Search panel dynamically.
     * New fields in MongoDB auto-appear here with no code changes.
     */
    @GetMapping("/api/search/field-config")
    public List<FieldConfigResponse> fieldConfig() {
        return fieldConfigService.getFieldConfig();
    }

    private int parseIntOr(String val, int def) {
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    private Map<String, String> normalizeFilters(Map<String, String> rawFilters) {
        Map<String, String> filters = new HashMap<>();
        if (rawFilters == null) return filters;
        rawFilters.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                filters.put(k, v);
            }
        });
        return filters;
    }

    private List<ExportColumnRequest> normalizeColumns(List<ExportColumnRequest> rawColumns) {
        if (rawColumns == null || rawColumns.isEmpty()) {
            return List.of(new ExportColumnRequest("reference", "Reference"));
        }
        Map<String, ExportColumnRequest> unique = new LinkedHashMap<>();
        for (ExportColumnRequest column : rawColumns) {
            if (column == null) continue;
            String key = column.getKey() == null ? "" : column.getKey().trim();
            if (key.isEmpty()) continue;
            String label = column.getLabel() == null || column.getLabel().isBlank() ? key : column.getLabel().trim();
            unique.putIfAbsent(key, new ExportColumnRequest(key, label));
        }
        if (unique.isEmpty()) {
            return List.of(new ExportColumnRequest("reference", "Reference"));
        }
        return List.copyOf(unique.values());
    }
}
