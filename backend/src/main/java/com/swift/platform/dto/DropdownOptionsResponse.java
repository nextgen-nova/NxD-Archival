package com.swift.platform.dto;
import lombok.Data;
import java.util.List;

@Data
public class DropdownOptionsResponse {
    // ── Core classification ────────────────────────────────────────────────
    private List<String> formats;
    private List<String> messageCodes;
    private List<String> types;
    private List<String> mtTypes;
    private List<String> mxTypes;
    private List<String> allMtMxTypes;
    private List<String> statuses;
    private List<String> phases;
    private List<String> actions;
    private List<String> ioDirections;
    private List<String> directions;

    // ── Routing ────────────────────────────────────────────────────────────
    private List<String> networkProtocols;
    private List<String> networks;
    private List<String> networkChannels;
    private List<String> backendChannels;
    private List<String> networkPriorities;
    private List<String> networkStatuses;
    private List<String> deliveryModes;
    private List<String> services;

    // ── Parties ────────────────────────────────────────────────────────────
    private List<String> senders;
    private List<String> receivers;
    private List<String> countries;
    private List<String> originCountries;
    private List<String> destinationCountries;

    // ── Ownership & workflow ───────────────────────────────────────────────
    private List<String> owners;
    private List<String> ownerUnits;
    private List<String> workflows;
    private List<String> workflowModels;
    private List<String> sourceSystems;
    private List<String> originatorApplications;

    // ── Financial ─────────────────────────────────────────────────────────
    private List<String> currencies;

    // ── Processing ────────────────────────────────────────────────────────
    private List<String> processingTypes;
    private List<String> processPriorities;
    private List<String> profileCodes;
    private List<String> environments;

    // ── AML / Compliance ──────────────────────────────────────────────────
    private List<String> amlStatuses;

    // ── Message flags ─────────────────────────────────────────────────────
    private List<String> finCopies;
    private List<String> finCopyServices;
    private List<String> messagePriorities;
    private List<String> nackCodes;
    private List<String> copyIndicators;

    // ── Reasons ───────────────────────────────────────────────────────────
    private List<String> reasons;
}
