package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.dto.FailureDTO;
import com.swift.platform.dto.PagedResponse;
import com.swift.platform.service.AuditService;
import com.swift.platform.service.FailureService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/failures")
@RequiredArgsConstructor
public class FailureController {

    private final FailureService failureService;
    private final AuditService auditService;

    @GetMapping
    public PagedResponse<FailureDTO> search(
            @RequestParam(required = false) String messageReference,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String inputType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String freeText,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {

        String employeeId = (String) req.getAttribute("employeeId");
        auditService.log(employeeId, "FAILURE_SEARCH",
                "ref=" + messageReference + " errorCode=" + errorCode, req.getRemoteAddr());

        return failureService.search(
                messageReference, errorCode, stage, inputType,
                startDate, endDate, freeText, page, size
        );
    }

    @GetMapping("/dropdown-options")
    public ApiResponse<Map<String, List<String>>> dropdownOptions() {
        return ApiResponse.ok(failureService.getDropdownOptions());
    }
}
