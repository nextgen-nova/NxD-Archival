package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.dto.ReportGenerateRequest;
import com.swift.platform.dto.ReportTemplateFormatRequest;
import com.swift.platform.dto.ReportTemplateRequest;
import com.swift.platform.dto.ReportTemplateResponse;
import com.swift.platform.service.AuditService;
import com.swift.platform.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final AuditService auditService;

    @GetMapping("/templates")
    public ApiResponse<List<ReportTemplateResponse>> templates(HttpServletRequest request) {
        return ApiResponse.ok(reportService.listTemplates(employeeId(request)));
    }

    @GetMapping("/definitions")
    public ApiResponse<List<Map<String, Object>>> definitions() {
        return ApiResponse.ok(reportService.listDefinitions());
    }

    @PostMapping("/templates")
    public ApiResponse<ReportTemplateResponse> saveTemplate(@RequestBody ReportTemplateRequest payload,
                                                            HttpServletRequest request) {
        ReportTemplateResponse response = reportService.saveTemplate(employeeId(request), payload);
        auditService.log(employeeId(request), "REPORT_TEMPLATE_SAVE", response.getCriteriaName(), request.getRemoteAddr());
        return ApiResponse.ok("Template saved.", response);
    }

    @DeleteMapping("/templates/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String templateId,
                                            HttpServletRequest request) {
        reportService.deleteTemplate(employeeId(request), templateId);
        auditService.log(employeeId(request), "REPORT_TEMPLATE_DELETE", templateId, request.getRemoteAddr());
        return ApiResponse.ok("Template deleted.", null);
    }

    @PostMapping("/templates/{templateId}/format")
    public ApiResponse<ReportTemplateResponse> updateTemplateFormat(@PathVariable String templateId,
                                                                    @RequestBody ReportTemplateFormatRequest payload,
                                                                    HttpServletRequest request) {
        ReportTemplateResponse response = reportService.updateTemplateFormat(employeeId(request), templateId, payload.getFormat());
        auditService.log(employeeId(request), "REPORT_TEMPLATE_FORMAT", templateId + " -> " + response.getFormat(), request.getRemoteAddr());
        return ApiResponse.ok("Template format updated.", response);
    }

    @PostMapping("/generate")
    public ResponseEntity<org.springframework.core.io.Resource> generate(@RequestBody ReportGenerateRequest payload,
                                                                         HttpServletRequest request) {
        try {
            ReportService.ReportDownload download = reportService.generate(employeeId(request), payload);
            auditService.log(employeeId(request), "REPORT_GENERATE", download.fileName(), request.getRemoteAddr());
            return downloadResponse(download);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody ReportGenerateRequest payload,
                                                    HttpServletRequest request) {
        try {
            return ApiResponse.ok(reportService.preview(employeeId(request), payload));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private ResponseEntity<org.springframework.core.io.Resource> downloadResponse(ReportService.ReportDownload download) {
        MediaType mediaType = download.contentType() == null || download.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(download.contentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .contentType(mediaType)
                .body(download.resource());
    }

    private String employeeId(HttpServletRequest request) {
        return (String) request.getAttribute("employeeId");
    }
}
