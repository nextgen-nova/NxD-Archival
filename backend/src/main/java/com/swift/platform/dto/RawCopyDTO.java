package com.swift.platform.dto;

import lombok.Data;

/**
 * DTO for a single document from the amp_raw_copies collection.
 *
 * Collection fields:
 *   _id, messageReference, messageId, rawInput (XML),
 *   inputType, source, isDuplicate, currentStatus,
 *   senderAddress, receiverAddress, messageTypeCode,
 *   protocol, direction, ampDateReceived, receivedAt, _class
 */
@Data
public class RawCopyDTO {
    private String  id;
    private String  messageReference;   // links to main message
    private String  messageId;
    private String  rawInput;           // raw XML/FIN content
    private String  inputType;          // e.g. AMP_MT
    private String  source;             // e.g. UNKNOWN
    private Boolean isDuplicate;
    private String  currentStatus;
    private String  senderAddress;
    private String  receiverAddress;
    private String  messageTypeCode;    // e.g. MT103
    private String  protocol;           // e.g. Swift-FIN
    private String  direction;          // INBOUND / OUTBOUND / ROUTING
    private String  ampDateReceived;
    private String  receivedAt;
}