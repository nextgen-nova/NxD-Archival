package com.swift.platform.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Describes one searchable field — returned by /api/search/field-config.
 * The frontend renders search inputs and result columns purely from this config.
 * When a new field appears in MongoDB, the backend auto-discovers it and
 * returns it here — no frontend code change needed.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FieldConfigResponse {

    /** Unique field key — matches the MongoDB message.* field name */
    private String key;

    /** Human-readable label shown in the UI */
    private String label;

    /** Group for organising in the field picker */
    private String group;

    /**
     * Input type:
     *   select       — dropdown of distinct values from DB
     *   text         — free text input
     *   text-wide    — full-width text input
     *   date-range   — dual date picker (creation date)
     *   date-range2  — dual date picker (other date fields)
     *   amount-range — min/max number inputs
     *   seq-range    — min/max sequence number inputs
     *   boolean      — YES/NO dropdown
     */
    private String type;

    /** Distinct values from DB for select fields (populated at runtime) */
    private List<String> options;

    /** Exact param name sent to /api/search backend */
    private String backendParam;

    /** Result table column label (null = no result column) */
    private String columnLabel;

    /** Whether this field appears as a result table column */
    private boolean showInTable;
}
