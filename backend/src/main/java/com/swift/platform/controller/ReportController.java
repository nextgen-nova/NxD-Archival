package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.dto.ReportGenerateRequest;
import com.swift.platform.dto.ReportHistoryPageResponse;
import com.swift.platform.dto.ReportHistoryResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

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

    @PostMapping("/templates/{templateId}/run")
    public ApiResponse<ReportHistoryResponse> runTemplate(@PathVariable String templateId,
                                                          HttpServletRequest request) {
        ReportHistoryResponse response = reportService.runTemplate(employeeId(request), templateId);
        auditService.log(employeeId(request), "REPORT_TEMPLATE_RUN", templateId, request.getRemoteAddr());
        return ApiResponse.ok("Report generation started.", response);
    }

    @PostMapping("/generate")
    public ApiResponse<ReportHistoryResponse> generate(@RequestBody ReportGenerateRequest payload,
                                                       HttpServletRequest request) {
        try {
            ReportHistoryResponse response = reportService.generate(employeeId(request), payload);
            auditService.log(employeeId(request), "REPORT_GENERATE", response.getFileName(), request.getRemoteAddr());
            return ApiResponse.ok("Report generated.", response);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/history")
    public ApiResponse<ReportHistoryPageResponse> history(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "10") int size,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(reportService.listHistory(employeeId(request), page, size));
    }

    @PostMapping("/history/refresh")
    public ApiResponse<ReportHistoryPageResponse> refresh(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "10") int size,
                                                          HttpServletRequest request) {
        auditService.log(employeeId(request), "REPORT_HISTORY_REFRESH", "page=" + page + ", size=" + size, request.getRemoteAddr());
        return ApiResponse.ok("History refreshed.", reportService.refreshHistory(employeeId(request), page, size));
    }

    @DeleteMapping("/history/{historyId}")
    public ApiResponse<Void> deleteHistory(@PathVariable String historyId,
                                           HttpServletRequest request) {
        reportService.deleteHistory(employeeId(request), historyId);
        auditService.log(employeeId(request), "REPORT_HISTORY_DELETE", historyId, request.getRemoteAddr());
        return ApiResponse.ok("History deleted.", null);
    }

    @GetMapping("/history/{historyId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String historyId,
                                                                         HttpServletRequest request) {
        ReportService.ReportDownload download;
        try {
            download = reportService.download(employeeId(request), historyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
        auditService.log(employeeId(request), "REPORT_HISTORY_DOWNLOAD", historyId, request.getRemoteAddr());
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
