package com.swift.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/**
 * Response wrapper for raw copies grouped by messageReference.
 */
@Data
@AllArgsConstructor
public class RawCopiesResponse {
    private String           messageReference;
    private int              totalCopies;
    private List<RawCopyDTO> copies;
}