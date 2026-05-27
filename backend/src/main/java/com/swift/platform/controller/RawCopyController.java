package com.swift.platform.controller;

import com.swift.platform.dto.ApiResponse;
import com.swift.platform.dto.PagedResponse;
import com.swift.platform.dto.RawCopyDTO;
import com.swift.platform.dto.RawCopiesResponse;
import com.swift.platform.service.AuditService;
import com.swift.platform.service.RawCopyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the amp_raw_copies collection.
 *
 * GET /api/raw-copies                       — search/list with filters
 * GET /api/raw-copies/by-ref/{msgRef}       — all copies for a messageReference
 * GET /api/raw-copies/dropdown-options      — distinct values for filter dropdowns
 */
@RestController
@RequestMapping("/api/raw-copies")
@RequiredArgsConstructor
public class RawCopyController {

    private final RawCopyService rawCopyService;
    private final AuditService   auditService;

    @GetMapping
    public PagedResponse<RawCopyDTO> search(
            @RequestParam(required = false) String messageReference,
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String receiver,
            @RequestParam(required = false) String messageTypeCode,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String currentStatus,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String inputType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean isDuplicate,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String freeText,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {

        String employeeId = (String) req.getAttribute("employeeId");
        auditService.log(employeeId, "RAW_COPY_SEARCH",
                "ref=" + messageReference + " type=" + messageTypeCode, req.getRemoteAddr());

        return rawCopyService.search(
                messageReference, messageId, sender, receiver,
                messageTypeCode, direction, currentStatus, protocol,
                inputType, source, isDuplicate, startDate, endDate, freeText,
                page, size);
    }

    @GetMapping("/by-ref/{messageReference}")
    public ApiResponse<RawCopiesResponse> getByRef(
            @PathVariable String messageReference,
            HttpServletRequest req) {
        String employeeId = (String) req.getAttribute("employeeId");
        auditService.log(employeeId, "RAW_COPY_BY_REF", messageReference, req.getRemoteAddr());
        return ApiResponse.ok(rawCopyService.getByMessageReference(messageReference));
    }

    @PostMapping("/by-refs")
    public ApiResponse<Map<String, List<RawCopyDTO>>> getByRefs(
            @RequestBody List<String> messageReferences,
            HttpServletRequest req) {
        String employeeId = (String) req.getAttribute("employeeId");
        int refCount = messageReferences == null ? 0 : messageReferences.size();
        auditService.log(employeeId, "RAW_COPY_BY_REFS", "count=" + refCount, req.getRemoteAddr());
        return ApiResponse.ok(rawCopyService.getByMessageReferences(messageReferences));
    }

    @GetMapping("/dropdown-options")
    public ApiResponse<Map<String, List<String>>> dropdownOptions() {
        return ApiResponse.ok(rawCopyService.getDropdownOptions());
    }
}
