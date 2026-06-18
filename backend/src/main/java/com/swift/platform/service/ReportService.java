package com.swift.platform.service;

import com.swift.platform.dto.ReportGenerateRequest;
import com.swift.platform.dto.ReportTemplateRequest;
import com.swift.platform.dto.ReportTemplateResponse;
import com.swift.platform.config.AppConfig;
import com.swift.platform.model.ReportTemplate;
import com.swift.platform.repository.ReportTemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.bson.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final String DEFAULT_USER = "A0016699";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final List<String> VALID_FORMATS = List.of("PDF", "Excel", "CSV");
    private static final Color PDF_TEXT = new Color(15, 23, 42);
    private static final Color PDF_MUTED_TEXT = new Color(71, 85, 105);
    private static final Color PDF_BORDER = new Color(203, 213, 225);
    private static final Color PDF_SECTION_BG = new Color(232, 240, 254);
    private static final Color PDF_HEADER_BG = new Color(241, 245, 249);
    private static final Color PDF_FOOTER_BG = new Color(239, 246, 255);
    private static final Map<String, String> REPORT_TITLES = Map.ofEntries(
            Map.entry("MRM001", "MEPS+ MONTHLY TRANSACTION TOTAL"),
            Map.entry("MRM002", "MONTHLY TRANSACTION TOTAL OF ALL DEPARTMENTS"),
            Map.entry("SWM002", "MONTHLY TRANSACTION TOTAL OF ALL DEPARTMENTS"),
            Map.entry("SWD003", "DETAILS OF INCOMING MESSAGES"),
            Map.entry("SWD004", "DETAILS OF OUTGOING MESSAGES"),
            Map.entry("SWS950", "DETAILS OF MT950 MESSAGES - CLOSING BALANCE"),
            Map.entry("UTR001", "TRAFFIC ANALYSIS REPORT - USER TRAFFIC"),
            Map.entry("ISN001", "TRAFFIC ANALYSIS REPORT - ISN GAPS"),
            Map.entry("MSGNACK", "MESSAGES NACKED"),
            Map.entry("OSN001", "OSN GAPS"),
            Map.entry("FINMSG", "AUDIT ANALYSIS REPORT - FINANCIAL MESSAGES"),
            Map.entry("DBEXT", "DB EXTRACT"),
            Map.entry("DUPMSG", "AUDIT ANALYSIS REPORT - POSSIBLE DUPLICATE MESSAGES"),
            Map.entry("IDM001", "DORMANT ID REPORT"),
            Map.entry("IDM002", "LOGON VALIDATION REPORT"),
            Map.entry("IDM003", "ID LISTING REPORT"),
            Map.entry("IDM004", "LOGIN FAILURE REPORT")
    );

    private final ReportTemplateRepository reportTemplateRepository;
    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;
    private final ReportQueryRegistry reportQueryRegistry;

    @PostConstruct
    void seedDefaults() {
        if (!reportTemplateRepository.existsByCreatedBy(DEFAULT_USER)) {
            reportTemplateRepository.save(ReportTemplate.builder()
                    .createdBy(DEFAULT_USER)
                    .criteriaName("SWD003")
                    .lastModifier(DEFAULT_USER)
                    .creationDate(Instant.now().minus(Duration.ofDays(8)))
                    .profile("ACU")
                    .format("PDF")
                    .criteria(defaultCriteria("custom", "SWD003", Map.of(
                            "bankName", "HBACED",
                            "messageFormat", "All - MT & MX"
                    )))
                    .build());
        }
    }

    public List<ReportTemplateResponse> listTemplates(String employeeId) {
        return reportTemplateRepository.findByCreatedByOrderByCreationDateDesc(normalizeEmployee(employeeId))
                .stream()
                .map(this::toTemplateResponse)
                .toList();
    }

    public List<Map<String, Object>> listDefinitions() {
        List<Document> definitions = mongoTemplate.getCollection(appConfig.getReportDefinitionsCollection())
                .find(new Document("enabled", new Document("$ne", false)))
                .into(new ArrayList<>());
        return definitions.stream()
                .sorted(Comparator.comparing(definition -> blankToDefault(definition.getString("reportCode"), "")))
                .map(this::definitionResponse)
                .toList();
    }

    public ReportTemplateResponse saveTemplate(String employeeId, ReportTemplateRequest request) {
        String user = normalizeEmployee(employeeId);
        ReportTemplate template = ReportTemplate.builder()
                .createdBy(user)
                .criteriaName(blankToDefault(request.getCriteriaName(), "Saved Criteria"))
                .lastModifier(user)
                .creationDate(Instant.now())
                .profile(blankToDefault(request.getProfile(), "ACU"))
                .format(normalizeFormat(request.getFormat()))
                .criteria(sanitizeCriteria(request.getCriteria()))
                .build();
        return toTemplateResponse(reportTemplateRepository.save(template));
    }

    public void deleteTemplate(String employeeId, String templateId) {
        ReportTemplate template = reportTemplateRepository.findByIdAndCreatedBy(templateId, normalizeEmployee(employeeId))
                .orElseThrow(() -> new IllegalArgumentException("Template not found."));
        reportTemplateRepository.delete(template);
    }

    public ReportTemplateResponse updateTemplateFormat(String employeeId, String templateId, String format) {
        ReportTemplate template = reportTemplateRepository.findByIdAndCreatedBy(templateId, normalizeEmployee(employeeId))
                .orElseThrow(() -> new IllegalArgumentException("Template not found."));
        template.setFormat(normalizeFormat(format));
        return toTemplateResponse(reportTemplateRepository.save(template));
    }

    public ReportDownload generate(String employeeId, ReportGenerateRequest request) {
        Map<String, Object> criteria = sanitizeCriteria(request.getCriteria());
        criteria.put("generatedBy", normalizeEmployee(employeeId));
        String format = normalizeFormat(request.getFormat());
        validateRange(criteria);
        byte[] bytes = buildArtifactBytes(criteria, format);
        String fileName = buildFileName(normalizeEmployee(employeeId), stringValue(criteria.get("reportCode")), format);
        return new ReportDownload(new ByteArrayResource(bytes), fileName, contentType(format));
    }

    public record ReportDownload(Resource resource, String fileName, String contentType) {}

    public Map<String, Object> preview(String employeeId, ReportGenerateRequest request) {
        Map<String, Object> criteria = sanitizeCriteria(request.getCriteria());
        criteria.put("generatedBy", normalizeEmployee(employeeId));
        validateRange(criteria);
        ReportLayout dynamicLayout = buildDynamicReportLayout(criteria);
        if (dynamicLayout == null) {
            throw missingReportDefinition(criteria);
        }
        return previewPayload(dynamicLayout);
    }

    private ReportTemplateResponse toTemplateResponse(ReportTemplate template) {
        return ReportTemplateResponse.builder()
                .id(template.getId())
                .criteriaName(template.getCriteriaName())
                .createdBy(template.getCreatedBy())
                .lastModifier(template.getLastModifier())
                .creationDate(template.getCreationDate())
                .profile(template.getProfile())
                .format(template.getFormat())
                .criteria(template.getCriteria())
                .build();
    }

    private Map<String, Object> definitionResponse(Document definition) {
        Map<String, Object> response = new LinkedHashMap<>();
        String reportCode = blankToDefault(definition.getString("reportCode"), "");
        response.put("reportCode", reportCode);
        response.put("code", reportCode);
        response.put("reportName", blankToDefault(definition.getString("reportName"), reportHeader(reportCode)));
        response.put("displayName", blankToDefault(definition.getString("displayName"), reportCode));
        response.put("category", blankToDefault(definition.getString("category"), "Custom Reports"));
        response.put("collection", blankToDefault(definition.getString("collection"), "messages"));
        response.put("collections", definitionCollectionsResponse(definition));
        response.put("filters", definitionFiltersResponse(definition));
        return response;
    }

    private Map<String, Object> definitionCollectionsResponse(Document definition) {
        Map<String, Object> collections = new LinkedHashMap<>();
        Document configured = documentAt(definition, "collections");
        configured.forEach((alias, value) -> collections.put(alias, Objects.toString(value, "")));
        return collections;
    }

    private List<Map<String, Object>> definitionFiltersResponse(Document definition) {
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Document filter : documentList(definition.get("filters"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", filter.getString("name"));
            item.put("label", blankToDefault(filter.getString("label"), titleize(filter.getString("name"))));
            item.put("type", blankToDefault(filter.getString("type"), "text"));
            item.put("required", Boolean.TRUE.equals(filter.getBoolean("required")));
            item.put("values", stringList(filter.get("values")));
            filters.add(item);
        }
        return filters;
    }

    private Map<String, Object> defaultCriteria(String category, String reportCode, Map<String, Object> extras) {
        Instant now = Instant.now();
        Instant start = now.minus(Duration.ofDays(7));
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("category", category);
        criteria.put("reportCode", reportCode);
        criteria.put("startDate", start.toString());
        criteria.put("endDate", now.toString());
        criteria.put("downloadFormat", "PDF");
        criteria.putAll(extras);
        return criteria;
    }

    private Document defaultMrm002Definition() {
        return Document.parse("""
                {
                  "_id": "report_definition:MRM002",
                  "reportCode": "MRM002",
                  "reportName": "MONTHLY TRANSACTION TOTAL OF ALL DEPARTMENTS",
                  "displayName": "MRM002 - Monthly Transaction Total of All Departments",
                  "category": "Custom Reports",
                  "collection": "messages",
                  "enabled": true,
                  "layout": {
                    "systemTitle": "SCBD SWIFT SYSTEM",
                    "orientation": "LANDSCAPE",
                    "pageSize": "A4",
                    "showCriteria": true,
                    "showRunDate": true,
                    "showPageNumber": true,
                    "showEndOfReport": true
                  },
                  "filters": [
                    { "name": "fromDate", "label": "From Date", "type": "datetime", "required": true, "dbPath": "header.dateCreated", "operator": "$gte" },
                    { "name": "toDate", "label": "To Date", "type": "datetime", "required": true, "dbPath": "header.dateCreated", "operator": "$lte" },
                    { "name": "department", "label": "Department", "type": "text", "dbPaths": ["header.owner", "owner", "department", "ownerUnit"], "operator": "$regex" },
                    { "name": "messageFormat", "label": "Message Format", "type": "select", "values": ["All", "MT Only", "MX Only"], "dbPath": "messageFamily", "operator": "$eq" },
                    { "name": "messageType", "label": "Message Type", "type": "text", "dbPaths": ["header.messageTypeCode", "messageType", "messageTypeCode"], "operator": "$regex" },
                    { "name": "currency", "label": "Currency", "type": "text", "dbPaths": ["extractedFields.currency", "currency"], "operator": "$regex" },
                    { "name": "status", "label": "Status", "type": "select", "values": ["Any", "Success", "Failed", "Pending", "Repaired", "Rejected", "Cancelled"], "dbPaths": ["status.current", "status.reason", "status.phase", "status.action"], "operator": "$regex" },
                    { "name": "direction", "label": "Direction", "type": "select", "values": ["All", "Inbound", "Outbound"], "dbPaths": ["protocolParams.FIN.ApplicationHeaderBlock.DirectionID", "header.direction", "direction"], "operator": "$direction" },
                    { "name": "sender", "label": "Sender BIC", "type": "text", "dbPaths": ["header.senderAddress", "protocolParams.requestor", "protocolParams.requester"], "operator": "$regex" },
                    { "name": "receiver", "label": "Receiver BIC", "type": "text", "dbPaths": ["header.receiverAddress", "protocolParams.responder"], "operator": "$regex" },
                    { "name": "branch", "label": "Branch", "type": "text", "dbPaths": ["header.backendChannel", "header.networkChannel", "backendChannel", "networkChannel"], "operator": "$regex" }
                  ],
                  "baseMatch": {
                    "messageFamily": { "$in": ["MT", "MX"] },
                    "$or": [
                      { "header.messageTypeCode": { "$exists": true } },
                      { "messageType": { "$exists": true } },
                      { "messageTypeCode": { "$exists": true } }
                    ]
                  },
                  "commonStages": [
                    {
                      "$addFields": {
                        "departmentName": { "$ifNull": ["$header.owner", { "$ifNull": ["$owner", { "$ifNull": ["$department", { "$ifNull": ["$ownerUnit", "None"] }] }] }] },
                        "messageTypeFinal": { "$ifNull": ["$header.messageTypeCode", { "$ifNull": ["$messageType", { "$ifNull": ["$messageTypeCode", "-"] }] }] },
                        "messageFamilyFinal": { "$ifNull": ["$messageFamily", "-"] },
                        "currencyFinal": { "$ifNull": ["$extractedFields.currency", { "$ifNull": ["$currency", "N/A"] }] },
                        "amountFinal": { "$convert": { "input": { "$ifNull": ["$extractedFields.amount", { "$ifNull": ["$amount", 0] }] }, "to": "double", "onError": 0, "onNull": 0 } },
                        "directionFinal": { "$ifNull": ["$protocolParams.FIN.ApplicationHeaderBlock.DirectionID", { "$ifNull": ["$header.direction", { "$ifNull": ["$direction", "-"] }] }] },
                        "statusRaw": {
                          "$concat": [
                            { "$ifNull": ["$status.current", ""] }, " ",
                            { "$ifNull": ["$status.reason", ""] }, " ",
                            { "$ifNull": ["$status.phase", ""] }, " ",
                            { "$ifNull": ["$status.action", ""] }
                          ]
                        }
                      }
                    },
                    {
                      "$addFields": {
                        "statusBucket": {
                          "$switch": {
                            "branches": [
                              { "case": { "$regexMatch": { "input": "$statusRaw", "regex": "reject", "options": "i" } }, "then": "Rejected" },
                              { "case": { "$regexMatch": { "input": "$statusRaw", "regex": "cancel", "options": "i" } }, "then": "Cancelled" },
                              { "case": { "$regexMatch": { "input": "$statusRaw", "regex": "repair", "options": "i" } }, "then": "Repaired" },
                              { "case": { "$regexMatch": { "input": "$statusRaw", "regex": "pend|wait|progress", "options": "i" } }, "then": "Pending" },
                              { "case": { "$regexMatch": { "input": "$statusRaw", "regex": "fail|nack|nak|error", "options": "i" } }, "then": "Failed" }
                            ],
                            "default": "Success"
                          }
                        },
                        "isDebit": {
                          "$or": [
                            { "$eq": ["$directionFinal", "O"] },
                            { "$regexMatch": { "input": "$directionFinal", "regex": "out", "options": "i" } }
                          ]
                        },
                        "isMt": {
                          "$or": [
                            { "$eq": ["$messageFamilyFinal", "MT"] },
                            { "$regexMatch": { "input": "$messageTypeFinal", "regex": "^MT|^[0-9]{3}$", "options": "i" } }
                          ]
                        }
                      }
                    }
                  ],
                  "sections": [
                    {
                      "id": "reportSummary",
                      "title": "Report Summary",
                      "type": "keyValue",
                      "columns": ["Field", "Value"],
                      "pipeline": [
                        {
                          "$group": {
                            "_id": null,
                            "totalDepartmentsSet": { "$addToSet": "$departmentName" },
                            "totalTransactions": { "$sum": 1 },
                            "totalSuccessfulTransactions": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Success"] }, 1, 0] } },
                            "totalFailedTransactions": { "$sum": { "$cond": [{ "$in": ["$statusBucket", ["Failed", "Rejected"]] }, 1, 0] } },
                            "totalPendingTransactions": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Pending"] }, 1, 0] } },
                            "grandTotalAmount": { "$sum": "$amountFinal" },
                            "currencySet": { "$addToSet": "$currencyFinal" }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "Report Name": "MONTHLY TRANSACTION TOTAL OF ALL DEPARTMENTS",
                            "Report Code": "MRM002",
                            "Report Month": "{{reportMonth}}",
                            "From Date": "{{fromDateDisplay}}",
                            "To Date": "{{toDateDisplay}}",
                            "Generated Date & Time": "{{generatedDateTime}}",
                            "Generated By": "{{generatedBy}}",
                            "Total Departments": { "$size": "$totalDepartmentsSet" },
                            "Total Transactions": "$totalTransactions",
                            "Total Successful Transactions": "$totalSuccessfulTransactions",
                            "Total Failed Transactions": "$totalFailedTransactions",
                            "Total Pending Transactions": "$totalPendingTransactions",
                            "Grand Total Amount": "$grandTotalAmount",
                            "Currency": { "$cond": [{ "$eq": [{ "$size": "$currencySet" }, 1] }, { "$arrayElemAt": ["$currencySet", 0] }, "Multiple"] }
                          }
                        }
                      ],
                      "fields": [
                        { "label": "Report Name", "field": "Report Name" },
                        { "label": "Report Code", "field": "Report Code" },
                        { "label": "Report Month", "field": "Report Month" },
                        { "label": "From Date", "field": "From Date" },
                        { "label": "To Date", "field": "To Date" },
                        { "label": "Generated Date & Time", "field": "Generated Date & Time" },
                        { "label": "Generated By", "field": "Generated By" },
                        { "label": "Total Departments", "field": "Total Departments" },
                        { "label": "Total Transactions", "field": "Total Transactions" },
                        { "label": "Total Successful Transactions", "field": "Total Successful Transactions" },
                        { "label": "Total Failed Transactions", "field": "Total Failed Transactions" },
                        { "label": "Total Pending Transactions", "field": "Total Pending Transactions" },
                        { "label": "Grand Total Amount", "field": "Grand Total Amount", "format": "amount" },
                        { "label": "Currency", "field": "Currency" }
                      ]
                    },
                    {
                      "id": "departmentSummary",
                      "title": "Department-wise Summary",
                      "type": "table",
                      "pipeline": [
                        {
                          "$group": {
                            "_id": { "department": "$departmentName", "currency": "$currencyFinal" },
                            "totalTransactions": { "$sum": 1 },
                            "successfulTransactions": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Success"] }, 1, 0] } },
                            "failedTransactions": { "$sum": { "$cond": [{ "$in": ["$statusBucket", ["Failed", "Rejected"]] }, 1, 0] } },
                            "pendingTransactions": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Pending"] }, 1, 0] } },
                            "repairedTransactions": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Repaired"] }, 1, 0] } },
                            "totalDebitAmount": { "$sum": { "$cond": ["$isDebit", "$amountFinal", 0] } },
                            "totalCreditAmount": { "$sum": { "$cond": ["$isDebit", 0, "$amountFinal"] } }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "Department Name": "$_id.department",
                            "Total Transactions": "$totalTransactions",
                            "Successful Transactions": "$successfulTransactions",
                            "Failed Transactions": "$failedTransactions",
                            "Pending Transactions": "$pendingTransactions",
                            "Repaired Transactions": "$repairedTransactions",
                            "Total Debit Amount": "$totalDebitAmount",
                            "Total Credit Amount": "$totalCreditAmount",
                            "Net Amount": { "$subtract": ["$totalCreditAmount", "$totalDebitAmount"] },
                            "Currency": "$_id.currency"
                          }
                        },
                        { "$sort": { "Department Name": 1, "Currency": 1 } }
                      ],
                      "columns": ["Department Name", "Total Transactions", "Successful Transactions", "Failed Transactions", "Pending Transactions", "Repaired Transactions", "Total Debit Amount", "Total Credit Amount", "Net Amount", "Currency"]
                    },
                    {
                      "id": "mtBreakdown",
                      "title": "MT Message Breakdown",
                      "type": "table",
                      "pipeline": [
                        { "$match": { "isMt": true } },
                        {
                          "$group": {
                            "_id": { "messageType": "$messageTypeFinal", "department": "$departmentName", "currency": "$currencyFinal" },
                            "transactionCount": { "$sum": 1 },
                            "successCount": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Success"] }, 1, 0] } },
                            "failedCount": { "$sum": { "$cond": [{ "$in": ["$statusBucket", ["Failed", "Rejected"]] }, 1, 0] } },
                            "totalAmount": { "$sum": "$amountFinal" }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "MT Message Type": "$_id.messageType",
                            "Message Description": {
                              "$switch": {
                                "branches": [
                                  { "case": { "$in": ["$_id.messageType", ["MT103", "103"]] }, "then": "Customer Credit Transfer" },
                                  { "case": { "$in": ["$_id.messageType", ["MT202", "202"]] }, "then": "Financial Institution Transfer" },
                                  { "case": { "$in": ["$_id.messageType", ["MT199", "199"]] }, "then": "Free Format Message" },
                                  { "case": { "$in": ["$_id.messageType", ["MT940", "940"]] }, "then": "Customer Statement" },
                                  { "case": { "$in": ["$_id.messageType", ["MT950", "950"]] }, "then": "Statement Message" }
                                ],
                                "default": "SWIFT MT Message"
                              }
                            },
                            "Department": "$_id.department",
                            "Transaction Count": "$transactionCount",
                            "Success Count": "$successCount",
                            "Failed Count": "$failedCount",
                            "Total Amount": "$totalAmount",
                            "Currency": "$_id.currency"
                          }
                        },
                        { "$sort": { "MT Message Type": 1, "Department": 1, "Currency": 1 } }
                      ],
                      "columns": ["MT Message Type", "Message Description", "Department", "Transaction Count", "Success Count", "Failed Count", "Total Amount", "Currency"]
                    },
                    {
                      "id": "mxBreakdown",
                      "title": "MX Message Breakdown",
                      "type": "table",
                      "pipeline": [
                        { "$match": { "isMt": false } },
                        {
                          "$group": {
                            "_id": { "messageType": "$messageTypeFinal", "department": "$departmentName", "currency": "$currencyFinal" },
                            "transactionCount": { "$sum": 1 },
                            "successCount": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Success"] }, 1, 0] } },
                            "failedCount": { "$sum": { "$cond": [{ "$in": ["$statusBucket", ["Failed", "Rejected"]] }, 1, 0] } },
                            "totalAmount": { "$sum": "$amountFinal" }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "MX Message Type": "$_id.messageType",
                            "Message Description": {
                              "$switch": {
                                "branches": [
                                  { "case": { "$regexMatch": { "input": "$_id.messageType", "regex": "pacs.008", "options": "i" } }, "then": "FI To FI Customer Credit Transfer" },
                                  { "case": { "$regexMatch": { "input": "$_id.messageType", "regex": "pacs.009", "options": "i" } }, "then": "Financial Institution Credit Transfer" },
                                  { "case": { "$regexMatch": { "input": "$_id.messageType", "regex": "pacs.002", "options": "i" } }, "then": "Payment Status Report" },
                                  { "case": { "$regexMatch": { "input": "$_id.messageType", "regex": "camt.053", "options": "i" } }, "then": "Bank To Customer Statement" },
                                  { "case": { "$regexMatch": { "input": "$_id.messageType", "regex": "camt.056", "options": "i" } }, "then": "Payment Cancellation Request" }
                                ],
                                "default": "SWIFT MX Message"
                              }
                            },
                            "Department": "$_id.department",
                            "Transaction Count": "$transactionCount",
                            "Success Count": "$successCount",
                            "Failed Count": "$failedCount",
                            "Total Amount": "$totalAmount",
                            "Currency": "$_id.currency"
                          }
                        },
                        { "$sort": { "MX Message Type": 1, "Department": 1, "Currency": 1 } }
                      ],
                      "columns": ["MX Message Type", "Message Description", "Department", "Transaction Count", "Success Count", "Failed Count", "Total Amount", "Currency"]
                    },
                    {
                      "id": "currencySummary",
                      "title": "Currency-wise Summary",
                      "type": "table",
                      "pipeline": [
                        {
                          "$group": {
                            "_id": "$currencyFinal",
                            "totalTransactions": { "$sum": 1 },
                            "debitCount": { "$sum": { "$cond": ["$isDebit", 1, 0] } },
                            "creditCount": { "$sum": { "$cond": ["$isDebit", 0, 1] } },
                            "totalDebitAmount": { "$sum": { "$cond": ["$isDebit", "$amountFinal", 0] } },
                            "totalCreditAmount": { "$sum": { "$cond": ["$isDebit", 0, "$amountFinal"] } },
                            "successCount": { "$sum": { "$cond": [{ "$eq": ["$statusBucket", "Success"] }, 1, 0] } },
                            "failedCount": { "$sum": { "$cond": [{ "$in": ["$statusBucket", ["Failed", "Rejected"]] }, 1, 0] } }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "Currency": "$_id",
                            "Total Transactions": "$totalTransactions",
                            "Debit Count": "$debitCount",
                            "Credit Count": "$creditCount",
                            "Total Debit Amount": "$totalDebitAmount",
                            "Total Credit Amount": "$totalCreditAmount",
                            "Net Amount": { "$subtract": ["$totalCreditAmount", "$totalDebitAmount"] },
                            "Success Count": "$successCount",
                            "Failed Count": "$failedCount"
                          }
                        },
                        { "$sort": { "Currency": 1 } }
                      ],
                      "columns": ["Currency", "Total Transactions", "Debit Count", "Credit Count", "Total Debit Amount", "Total Credit Amount", "Net Amount", "Success Count", "Failed Count"]
                    },
                    {
                      "id": "statusSummary",
                      "title": "Status-wise Summary",
                      "type": "table",
                      "pipeline": [
                        {
                          "$group": {
                            "_id": "$statusBucket",
                            "totalCount": { "$sum": 1 },
                            "totalAmount": { "$sum": "$amountFinal" },
                            "departments": { "$addToSet": "$departmentName" }
                          }
                        },
                        {
                          "$project": {
                            "_id": 0,
                            "Status": "$_id",
                            "Total Count": "$totalCount",
                            "Percentage": "$totalCount",
                            "Total Amount": "$totalAmount",
                            "Department Count": { "$size": "$departments" }
                          }
                        },
                        { "$sort": { "Status": 1 } }
                      ],
                      "columns": ["Status", "Total Count", "Percentage", "Total Amount", "Department Count"],
                      "fixedStatusRows": ["Success", "Failed", "Pending", "Repaired", "Rejected", "Cancelled"]
                    }
                  ]
                }
                """);
    }

    private String normalizeEmployee(String employeeId) {
        return employeeId == null || employeeId.isBlank() ? DEFAULT_USER : employeeId;
    }

    private String normalizeFormat(String format) {
        String normalized = blankToDefault(format, "PDF").trim();
        String canonical = switch (normalized.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "PDF";
            case "excel", "xls", "xlsx" -> "Excel";
            case "csv" -> "CSV";
            default -> normalized;
        };
        if (!VALID_FORMATS.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported report format: " + format);
        }
        return canonical;
    }

    private Map<String, Object> sanitizeCriteria(Map<String, Object> raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key != null && value != null) {
                    safe.put(key, value);
                }
            });
        }
        if (!safe.containsKey("category")) safe.put("category", "custom");
        if (!safe.containsKey("reportCode")) safe.put("reportCode", "SWD003");
        if (!safe.containsKey("downloadFormat")) safe.put("downloadFormat", "PDF");
        return safe;
    }

    private void validateRange(Map<String, Object> criteria) {
        String startValue = stringValue(criteria.get("startDate"));
        String endValue = stringValue(criteria.get("endDate"));
        if (startValue == null || endValue == null) {
            return;
        }
        Instant start = parseInstant(startValue);
        Instant end = parseInstant(endValue);
        if (start == null || end == null) {
            throw new IllegalArgumentException("Invalid date range.");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Start date must be earlier than end date.");
        }
        if (Duration.between(start, end).toDays() > 31) {
            throw new IllegalArgumentException("Date range cannot exceed 31 days.");
        }
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignore) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private byte[] buildArtifactBytes(Map<String, Object> criteria, String format) {
        ReportLayout dynamicLayout = buildDynamicReportLayout(criteria);
        if (dynamicLayout == null) {
            throw missingReportDefinition(criteria);
        }
        return switch (format) {
            case "CSV" -> buildCsvBytes(dynamicLayout);
            case "Excel" -> buildExcelBytes(dynamicLayout);
            default -> buildPdfBytes(dynamicLayout);
        };
    }

    private String buildFileName(String employeeId, String reportCode, String format) {
        String extension = switch (format) {
            case "Excel" -> "xlsx";
            case "CSV" -> "csv";
            default -> "pdf";
        };
        String stamp = FILE_TIME.format(LocalDateTime.now());
        return "Online_Generate_" + blankToDefault(reportCode, "REPORT") + "_" + employeeId + "_" + stamp + "." + extension;
    }

    private String contentType(String format) {
        return switch (format) {
            case "Excel" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "CSV" -> "text/csv;charset=UTF-8";
            default -> "application/pdf";
        };
    }

    private byte[] buildCsvBytes(ReportLayout layout) {
        List<String> rows = new ArrayList<>();
        rows.add(csvLine(layout.systemTitle));
        rows.add(csvLine(layout.reportTitle));
        rows.add(csvLine("Run Date", layout.runDate));
        rows.add("");
        if (!layout.criteriaRows.isEmpty()) {
            rows.add(csvLine("Criteria", ""));
            layout.criteriaRows.forEach(row -> rows.add(csvLine(row.toArray(String[]::new))));
            rows.add("");
        }
        for (LayoutBlock block : layout.blocks) {
            if (block instanceof TextBlock textBlock) {
                rows.add(csvLine(textBlock.text));
            } else if (block instanceof TableBlock tableBlock) {
                if (tableBlock.title != null && !tableBlock.title.isBlank()) {
                    rows.add(csvLine(tableBlock.title));
                }
                rows.add(csvLine(tableBlock.headers.toArray(String[]::new)));
                if (tableBlock.rows.isEmpty()) {
                    rows.add(csvLine("No records found."));
                } else {
                    tableBlock.rows.forEach(row -> rows.add(csvLine(row.toArray(String[]::new))));
                }
                tableBlock.footerRows.forEach(row -> rows.add(csvLine(row.toArray(String[]::new))));
            }
            rows.add("");
        }
        rows.add(csvLine("Total Message Count", String.valueOf(layout.totalMessageCount)));
        rows.add("");
        rows.add(csvLine("END OF REPORT"));
        return String.join("\n", rows).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildExcelBytes(ReportLayout layout) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (hasTableBlocks(layout)) {
                writeSectionedExcel(workbook, layout);
                workbook.write(out);
                return out.toByteArray();
            }
            XSSFSheet sheet = workbook.createSheet("Report");
            int rowIndex = 0;
            CellStyle titleStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            CellStyle sectionStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionStyle.setFont(sectionFont);
            sectionStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sectionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle cellStyle = workbook.createCellStyle();
            cellStyle.setBorderBottom(BorderStyle.THIN);
            cellStyle.setBorderTop(BorderStyle.THIN);
            cellStyle.setBorderLeft(BorderStyle.THIN);
            cellStyle.setBorderRight(BorderStyle.THIN);

            rowIndex = writeExcelRow(sheet, rowIndex, List.of(layout.systemTitle), titleStyle);
            rowIndex = writeExcelRow(sheet, rowIndex, List.of(layout.reportTitle), titleStyle);
            rowIndex = writeExcelRow(sheet, rowIndex, List.of("Run Date", layout.runDate), null);
            rowIndex++;

            if (!layout.criteriaRows.isEmpty()) {
                rowIndex = writeExcelRow(sheet, rowIndex, List.of("Criteria"), sectionStyle);
                for (List<String> criteriaRow : layout.criteriaRows) {
                    rowIndex = writeExcelRow(sheet, rowIndex, criteriaRow, null);
                }
                rowIndex++;
            }

            for (LayoutBlock block : layout.blocks) {
                if (block instanceof TextBlock textBlock) {
                    rowIndex = writeExcelRow(sheet, rowIndex, List.of(textBlock.text), sectionStyle);
                } else if (block instanceof TableBlock tableBlock) {
                    if (tableBlock.title != null && !tableBlock.title.isBlank()) {
                        rowIndex = writeExcelRow(sheet, rowIndex, List.of(tableBlock.title), sectionStyle);
                    }
                    int headerRowIndex = rowIndex;
                    rowIndex = writeExcelRow(sheet, rowIndex, tableBlock.headers, headerStyle);
                    if (tableBlock.rows.isEmpty()) {
                        rowIndex = writeExcelRow(sheet, rowIndex, List.of("No records found."), cellStyle);
                    } else {
                        for (List<String> dataRow : tableBlock.rows) {
                            rowIndex = writeExcelRow(sheet, rowIndex, dataRow, cellStyle);
                        }
                    }
                    for (List<String> footerRow : tableBlock.footerRows) {
                        rowIndex = writeExcelRow(sheet, rowIndex, footerRow, sectionStyle);
                    }
                    if (sheet.getPaneInformation() == null) {
                        sheet.createFreezePane(0, headerRowIndex + 1);
                    }
                }
                rowIndex++;
            }

            rowIndex = writeExcelRow(sheet, rowIndex, List.of("Total Message Count", String.valueOf(layout.totalMessageCount)), sectionStyle);
            rowIndex = writeExcelRow(sheet, rowIndex + 1, List.of("END OF REPORT"), titleStyle);

            int maxCols = maxLayoutColumns(layout);
            for (int column = 0; column < maxCols; column++) {
                sheet.autoSizeColumn(column);
            }
            if (maxCols > 0) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 2), 0, maxCols - 1));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build Excel report.", exception);
        }
    }

    private void writeSectionedExcel(XSSFWorkbook workbook, ReportLayout layout) {
        CellStyle titleStyle = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        CellStyle cellStyle = workbook.createCellStyle();
        cellStyle.setBorderBottom(BorderStyle.THIN);
        cellStyle.setBorderTop(BorderStyle.THIN);
        cellStyle.setBorderLeft(BorderStyle.THIN);
        cellStyle.setBorderRight(BorderStyle.THIN);

        for (LayoutBlock block : layout.blocks) {
            if (!(block instanceof TableBlock tableBlock)) {
                continue;
            }
            XSSFSheet sheet = workbook.createSheet(uniqueSheetName(workbook, tableBlock.title));
            int rowIndex = 0;
            rowIndex = writeExcelRow(sheet, rowIndex, List.of(layout.systemTitle), titleStyle);
            rowIndex = writeExcelRow(sheet, rowIndex, List.of(layout.reportTitle), titleStyle);
            rowIndex = writeExcelRow(sheet, rowIndex, List.of("Run Date", layout.runDate), null);
            rowIndex++;
            rowIndex = writeExcelRow(sheet, rowIndex, List.of(tableBlock.title), titleStyle);
            int headerRowIndex = rowIndex;
            rowIndex = writeExcelRow(sheet, rowIndex, tableBlock.headers, headerStyle);
            if (tableBlock.rows.isEmpty()) {
                rowIndex = writeExcelRow(sheet, rowIndex, List.of("No records found."), cellStyle);
            } else {
                for (List<String> dataRow : tableBlock.rows) {
                    rowIndex = writeExcelRow(sheet, rowIndex, dataRow, cellStyle);
                }
            }
            for (List<String> footerRow : tableBlock.footerRows) {
                rowIndex = writeExcelRow(sheet, rowIndex, footerRow, cellStyle);
            }
            sheet.createFreezePane(0, headerRowIndex + 1);
            int maxCols = Math.max(1, tableBlock.headers.size());
            for (int column = 0; column < maxCols; column++) {
                sheet.autoSizeColumn(column);
            }
            if (maxCols > 0) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(headerRowIndex, Math.max(headerRowIndex, rowIndex), 0, maxCols - 1));
            }
        }
        if (workbook.getNumberOfSheets() == 0) {
            XSSFSheet sheet = workbook.createSheet("Report");
            writeExcelRow(sheet, 0, List.of(layout.systemTitle), titleStyle);
            writeExcelRow(sheet, 1, List.of(layout.reportTitle), titleStyle);
            writeExcelRow(sheet, 2, List.of("No report sections found."), cellStyle);
        }
    }

    private byte[] buildPdfBytes(ReportLayout layout) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(landscapeA4());
            document.addPage(page);
            float margin = 36f;
            float usableWidth = page.getMediaBox().getWidth() - margin * 2;
            float y = page.getMediaBox().getHeight() - margin;
            int pageNumber = 1;
            PDPageContentStream content = new PDPageContentStream(document, page);
            try {
                y = writePdfLine(content, margin, y, PDType1Font.HELVETICA_BOLD, 15f, layout.systemTitle, 20f);
                y = writePdfLine(content, margin, y, PDType1Font.HELVETICA_BOLD, 12f, layout.reportTitle, 18f);
                y = writePdfLine(content, margin, y, PDType1Font.HELVETICA, 10f, "Run Date: " + layout.runDate + "    Page " + pageNumber, 14f);
                y -= 4f;

                if (!layout.criteriaRows.isEmpty()) {
                    y = drawPdfSectionTitle(content, margin, y, usableWidth, "Criteria");
                    y = drawPdfKeyValueRows(content, margin, y, usableWidth, layout.criteriaRows);
                    y -= 8f;
                }

                for (LayoutBlock block : layout.blocks) {
                    if (y < 120f) {
                        content.close();
                        page = new PDPage(landscapeA4());
                        document.addPage(page);
                        pageNumber++;
                        content = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - margin;
                        y = writePdfLine(content, margin, y, PDType1Font.HELVETICA, 10f, "Page " + pageNumber, 14f);
                    }
                    if (block instanceof TextBlock textBlock) {
                        y = drawPdfSectionTitle(content, margin, y, usableWidth, textBlock.text);
                    } else if (block instanceof TableBlock tableBlock) {
                        if (tableBlock.title != null && !tableBlock.title.isBlank()) {
                            y = drawPdfSectionTitle(content, margin, y, usableWidth, tableBlock.title);
                        }
                        y = drawPdfTable(document, content, page, margin, y, usableWidth, tableBlock.headers, tableBlock.rows, tableBlock.footerRows);
                        page = document.getPage(document.getNumberOfPages() - 1);
                        y = currentPdfY;
                        content = currentPdfContent;
                    }
                    y -= 6f;
                }

                y -= 8f;
                y = drawPdfFooterRow(content, margin, y, usableWidth, "Total Message Count", String.valueOf(layout.totalMessageCount));
                y -= 10f;
                y = drawPdfEndMarker(content, margin, y, usableWidth);
            } finally {
                content.close();
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build PDF report.", exception);
        }
    }

    private int writeExcelRow(XSSFSheet sheet, int rowIndex, List<String> values, CellStyle style) {
        Row row = sheet.createRow(rowIndex++);
        for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
            Cell cell = row.createCell(cellIndex);
            cell.setCellValue(values.get(cellIndex));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
        return rowIndex;
    }

    private boolean hasTableBlocks(ReportLayout layout) {
        return layout.blocks.stream().anyMatch(TableBlock.class::isInstance);
    }

    private String uniqueSheetName(XSSFWorkbook workbook, String requestedName) {
        String baseName = safeSheetName(requestedName);
        String sheetName = baseName;
        int index = 2;
        while (workbook.getSheet(sheetName) != null) {
            String suffix = " " + index++;
            int baseLength = Math.min(baseName.length(), 31 - suffix.length());
            sheetName = baseName.substring(0, Math.max(1, baseLength)) + suffix;
        }
        return sheetName;
    }

    private int maxLayoutColumns(ReportLayout layout) {
        int maxCols = 2;
        for (List<String> row : layout.criteriaRows) {
            maxCols = Math.max(maxCols, row.size());
        }
        for (LayoutBlock block : layout.blocks) {
            if (block instanceof TableBlock tableBlock) {
                maxCols = Math.max(maxCols, tableBlock.headers.size());
                for (List<String> row : tableBlock.rows) {
                    maxCols = Math.max(maxCols, row.size());
                }
                for (List<String> row : tableBlock.footerRows) {
                    maxCols = Math.max(maxCols, row.size());
                }
            }
        }
        return maxCols;
    }

    private float writePdfLine(PDPageContentStream content,
                               float x,
                               float y,
                               PDType1Font font,
                               float size,
                               String text,
                               float lineGap) throws IOException {
        content.setNonStrokingColor(PDF_TEXT);
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
        return y - lineGap;
    }

    private PDRectangle landscapeA4() {
        return new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    }

    private float drawPdfSectionTitle(PDPageContentStream content, float x, float y, float width, String title) throws IOException {
        List<String> lines = wrapPdfText(sanitizePdfText(title), PDType1Font.HELVETICA_BOLD, 10f, width - 8f);
        float lineHeight = 12f;
        float boxHeight = Math.max(18f, lines.size() * lineHeight + 6f);
        content.setStrokingColor(PDF_BORDER);
        content.addRect(x, y - boxHeight, width, boxHeight);
        content.setNonStrokingColor(PDF_SECTION_BG);
        content.fill();
        content.setNonStrokingColor(PDF_TEXT);
        content.addRect(x, y - boxHeight, width, boxHeight);
        content.stroke();
        writePdfWrappedText(content, x + 4f, y - 12f, lines, PDType1Font.HELVETICA_BOLD, 10f, lineHeight);
        return y - boxHeight - 8f;
    }

    private float drawPdfFooterRow(PDPageContentStream content, float x, float y, float width, String label, String value) throws IOException {
        float rowHeight = 24f;
        float labelWidth = width * 0.32f;
        content.setStrokingColor(PDF_BORDER);
        content.setNonStrokingColor(PDF_FOOTER_BG);
        content.addRect(x, y - rowHeight, width, rowHeight);
        content.fill();
        content.setNonStrokingColor(PDF_TEXT);
        content.addRect(x, y - rowHeight, labelWidth, rowHeight);
        content.addRect(x + labelWidth, y - rowHeight, width - labelWidth, rowHeight);
        content.stroke();
        writePdfWrappedText(content, x + 6f, y - 15f, List.of(label), PDType1Font.HELVETICA_BOLD, 9f, 11f);
        writePdfWrappedText(content, x + labelWidth + 6f, y - 15f, List.of(value), PDType1Font.HELVETICA_BOLD, 9f, 11f);
        return y - rowHeight;
    }

    private float drawPdfEndMarker(PDPageContentStream content, float x, float y, float width) throws IOException {
        float rowHeight = 22f;
        content.setStrokingColor(PDF_BORDER);
        content.setNonStrokingColor(new Color(248, 250, 252));
        content.addRect(x, y - rowHeight, width, rowHeight);
        content.fill();
        content.setNonStrokingColor(PDF_MUTED_TEXT);
        content.addRect(x, y - rowHeight, width, rowHeight);
        content.stroke();
        writePdfWrappedText(content, x + (width / 2f) - 42f, y - 14f, List.of("END OF REPORT"), PDType1Font.HELVETICA_BOLD, 9f, 11f);
        return y - rowHeight;
    }

    private float drawPdfKeyValueRows(PDPageContentStream content, float x, float y, float width, List<List<String>> rows) throws IOException {
        float keyWidth = width * 0.22f;
        float valueWidth = width * 0.28f;
        float secondKeyWidth = width * 0.22f;
        float secondValueWidth = width - keyWidth - valueWidth - secondKeyWidth;
        float cellPadding = 3f;
        float lineHeight = 10f;
        for (List<String> row : rows) {
            List<String> key1Lines = row.size() > 0 ? wrapPdfText(blankToDefault(row.get(0), "-"), PDType1Font.HELVETICA_BOLD, 8f, keyWidth - cellPadding * 2) : List.of("-");
            List<String> value1Lines = row.size() > 1 ? wrapPdfText(blankToDefault(row.get(1), "-"), PDType1Font.HELVETICA, 8f, valueWidth - cellPadding * 2) : List.of("-");
            List<String> key2Lines = row.size() > 2 ? wrapPdfText(blankToDefault(row.get(2), "-"), PDType1Font.HELVETICA_BOLD, 8f, secondKeyWidth - cellPadding * 2) : List.of("-");
            List<String> value2Lines = row.size() > 3 ? wrapPdfText(blankToDefault(row.get(3), "-"), PDType1Font.HELVETICA, 8f, secondValueWidth - cellPadding * 2) : List.of("-");
            int maxLines = Math.max(Math.max(key1Lines.size(), value1Lines.size()), Math.max(key2Lines.size(), value2Lines.size()));
            float rowHeight = Math.max(16f, maxLines * lineHeight + 6f);
            float top = y;
            content.setStrokingColor(PDF_BORDER);
            content.addRect(x, top - rowHeight, keyWidth, rowHeight);
            content.addRect(x + keyWidth, top - rowHeight, valueWidth, rowHeight);
            content.addRect(x + keyWidth + valueWidth, top - rowHeight, secondKeyWidth, rowHeight);
            content.addRect(x + keyWidth + valueWidth + secondKeyWidth, top - rowHeight, secondValueWidth, rowHeight);
            content.stroke();
            writePdfWrappedText(content, x + cellPadding, top - 10f, key1Lines, PDType1Font.HELVETICA_BOLD, 8f, lineHeight);
            writePdfWrappedText(content, x + keyWidth + cellPadding, top - 10f, value1Lines, PDType1Font.HELVETICA, 8f, lineHeight);
            writePdfWrappedText(content, x + keyWidth + valueWidth + cellPadding, top - 10f, key2Lines, PDType1Font.HELVETICA_BOLD, 8f, lineHeight);
            writePdfWrappedText(content, x + keyWidth + valueWidth + secondKeyWidth + cellPadding, top - 10f, value2Lines, PDType1Font.HELVETICA, 8f, lineHeight);
            y -= rowHeight;
        }
        return y;
    }

    private PDPageContentStream currentPdfContent;
    private float currentPdfY;

    private float drawPdfTable(PDDocument document,
                               PDPageContentStream content,
                               PDPage page,
                               float x,
                               float y,
                               float width,
                               List<String> headers,
                               List<List<String>> rows,
                               List<List<String>> footerRows) throws IOException {
        int colCount = Math.max(headers.size(), 1);
        float colWidth = width / colCount;
        PDPageContentStream activeContent = content;
        PDPage activePage = page;
        float cursorY = y;

        cursorY = drawPdfTableHeader(activeContent, x, cursorY, headers, colWidth);
        List<List<String>> renderedRows = rows.isEmpty() ? List.of(List.of("No records found.")) : rows;
        for (List<String> row : renderedRows) {
            float rowHeight = computePdfRowHeight(row, colCount, colWidth, 8f);
            if (cursorY < 72f) {
                activeContent.close();
                activePage = new PDPage(landscapeA4());
                document.addPage(activePage);
                activeContent = new PDPageContentStream(document, activePage);
                cursorY = activePage.getMediaBox().getHeight() - 36f;
                cursorY = drawPdfTableHeader(activeContent, x, cursorY, headers, colWidth);
            }
            if (cursorY - rowHeight < 36f) {
                activeContent.close();
                activePage = new PDPage(landscapeA4());
                document.addPage(activePage);
                activeContent = new PDPageContentStream(document, activePage);
                cursorY = activePage.getMediaBox().getHeight() - 36f;
                cursorY = drawPdfTableHeader(activeContent, x, cursorY, headers, colWidth);
            }
            cursorY = drawPdfTableRow(activeContent, x, cursorY, row, colCount, colWidth, false);
        }
        for (List<String> footerRow : footerRows) {
            float rowHeight = computePdfRowHeight(footerRow, colCount, colWidth, 8f);
            if (cursorY < 72f) {
                activeContent.close();
                activePage = new PDPage(landscapeA4());
                document.addPage(activePage);
                activeContent = new PDPageContentStream(document, activePage);
                cursorY = activePage.getMediaBox().getHeight() - 36f;
                cursorY = drawPdfTableHeader(activeContent, x, cursorY, headers, colWidth);
            }
            if (cursorY - rowHeight < 36f) {
                activeContent.close();
                activePage = new PDPage(landscapeA4());
                document.addPage(activePage);
                activeContent = new PDPageContentStream(document, activePage);
                cursorY = activePage.getMediaBox().getHeight() - 36f;
                cursorY = drawPdfTableHeader(activeContent, x, cursorY, headers, colWidth);
            }
            cursorY = drawPdfTableRow(activeContent, x, cursorY, footerRow, colCount, colWidth, true);
        }
        currentPdfContent = activeContent;
        currentPdfY = cursorY;
        return cursorY;
    }

    private float drawPdfTableHeader(PDPageContentStream content, float x, float y, List<String> headers, float colWidth) throws IOException {
        float rowHeight = computePdfRowHeight(headers, headers.size(), colWidth, 8f);
        content.setNonStrokingColor(PDF_HEADER_BG);
        content.addRect(x, y - rowHeight, colWidth * headers.size(), rowHeight);
        content.fill();
        content.setNonStrokingColor(PDF_TEXT);
        return drawPdfTableRow(content, x, y, headers, headers.size(), colWidth, true);
    }

    private float drawPdfTableRow(PDPageContentStream content, float x, float y, List<String> values, int colCount, float colWidth, boolean bold) throws IOException {
        float rowHeight = computePdfRowHeight(values, colCount, colWidth, 8f);
        content.setStrokingColor(PDF_BORDER);
        content.setNonStrokingColor(PDF_TEXT);
        for (int column = 0; column < colCount; column++) {
            float cellX = x + column * colWidth;
            content.addRect(cellX, y - rowHeight, colWidth, rowHeight);
            content.stroke();
            String text = column < values.size() ? values.get(column) : "";
            writePdfCellText(content, cellX + 3f, y - 10f, colWidth - 6f, text, bold);
        }
        return y - rowHeight;
    }

    private void writePdfCellText(PDPageContentStream content, float x, float y, float width, String text, boolean bold) throws IOException {
        PDType1Font font = bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
        List<String> lines = wrapPdfText(blankToDefault(text, "-"), font, 8f, width);
        writePdfWrappedText(content, x, y, lines, font, 8f, 10f);
    }

    private void writePdfWrappedText(PDPageContentStream content,
                                     float x,
                                     float y,
                                     List<String> lines,
                                     PDType1Font font,
                                     float fontSize,
                                     float lineHeight) throws IOException {
        float cursorY = y;
        content.setNonStrokingColor(PDF_TEXT);
        for (String line : lines) {
            content.beginText();
            content.setFont(font, fontSize);
            content.newLineAtOffset(x, cursorY);
            content.showText(sanitizePdfText(blankToDefault(line, "-")));
            content.endText();
            cursorY -= lineHeight;
        }
    }

    private float computePdfRowHeight(List<String> values, int colCount, float colWidth, float fontSize) throws IOException {
        float contentWidth = colWidth - 6f;
        int maxLines = 1;
        for (int column = 0; column < colCount; column++) {
            String text = column < values.size() ? values.get(column) : "";
            maxLines = Math.max(maxLines, wrapPdfText(blankToDefault(text, "-"), PDType1Font.HELVETICA, fontSize, contentWidth).size());
        }
        return Math.max(16f, maxLines * 10f + 6f);
    }

    private List<String> wrapPdfText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        String sanitized = sanitizePdfText(blankToDefault(text, "-")).trim();
        if (sanitized.isEmpty()) {
            return List.of("-");
        }
        List<String> lines = new ArrayList<>();
        String[] paragraphs = sanitized.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : paragraphs) {
            if (current.isEmpty()) {
                current.append(word);
                continue;
            }
            String candidate = current + " " + word;
            if (pdfTextWidth(font, fontSize, candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                lines.add(current.toString());
                if (pdfTextWidth(font, fontSize, word) <= maxWidth) {
                    current.setLength(0);
                    current.append(word);
                } else {
                    lines.addAll(splitLongPdfWord(word, font, fontSize, maxWidth));
                    current.setLength(0);
                }
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("-") : lines;
    }

    private List<String> splitLongPdfWord(String word, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : word.toCharArray()) {
            String candidate = current.toString() + ch;
            if (!current.isEmpty() && pdfTextWidth(font, fontSize, candidate) > maxWidth) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private float pdfTextWidth(PDType1Font font, float fontSize, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    private String sanitizePdfText(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ");
    }

    private String escapeCsv(String value) {
        return value.replace("\"", "\"\"");
    }

    private String titleize(String key) {
        if (key == null || key.isBlank()) return "";
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }

    private String reportHeader(String reportCode) {
        return REPORT_TITLES.getOrDefault(reportCode, "SWIFT PLATFORM REPORT");
    }

    private String prettyInstant(Instant instant) {
        return DateTimeFormatter.ofPattern("d/M/yy HH:mm:ss").withZone(ZoneOffset.UTC).format(instant);
    }

    private String prettyDateTimeValue(Object value) {
        String string = stringValue(value);
        if (string == null) return "";
        Instant instant = parseInstant(string);
        if (instant == null) return string;
        return DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a").withZone(ZoneOffset.UTC).format(instant);
    }

    private ReportLayout buildReportLayout(Map<String, Object> criteria, ReportData data) {
        String reportCode = blankToDefault(stringValue(criteria.get("reportCode")), "SWD003");
        ReportLayout layout = new ReportLayout();
        layout.reportCode = reportCode;
        layout.systemTitle = "SCBD SWIFT SYSTEM";
        layout.reportTitle = reportHeader(reportCode);
        layout.runDate = prettyInstant(Instant.now());
        layout.criteriaRows = buildCriteriaRows(criteria);
        layout.totalMessageCount = data.totalMessages;

        switch (reportCode) {
            case "UTR001" -> {
                layout.blocks.add(textBlock("Summary Table"));
                layout.blocks.add(tableBlock(
                        "",
                        List.of("Bank", "Date", "Direction", "Message Type", "Status", "Count"),
                        data.summaryRows,
                        List.of()
                ));
                if (!data.detailRows.isEmpty()) {
                    DetailRow first = data.detailRows.get(0);
                    layout.blocks.add(textBlock("Detail"));
                    layout.blocks.add(tableBlock(
                            "",
                            List.of("Bank Name", "Message Type", "Status"),
                            List.of(List.of(blankToDefault(first.bank, "-"), blankToDefault(first.messageType, "-"), blankToDefault(first.status, "-"))),
                            List.of()
                    ));
                }
            }
            case "ISN001", "OSN001" -> {
                layout.blocks.add(textBlock("Logical Terminal: " + blankToDefault(data.primaryLogicalTerminal, "-")));
                List<List<String>> gapRows = data.gapRows.stream()
                        .map(row -> List.of(blankToDefault(row.leftRef, "-"), blankToDefault(row.rightRef, "-"), blankToDefault(row.missing, "0")))
                        .toList();
                layout.blocks.add(tableBlock(
                        reportCode.equals("ISN001") ? "Incoming Sequence Gaps" : "Outgoing Sequence Gaps",
                        List.of("Last MIR/OSN Before Gap", "First MIR/OSN After Gap", "Missing"),
                        gapRows,
                        List.of()
                ));
            }
            case "FINMSG" -> {
                List<List<String>> summaryRows = data.financialSummaryRows.stream()
                        .map(row -> List.of(row.sender, row.messageType, row.currency, String.valueOf(row.count), row.totalAmount))
                        .toList();
                layout.blocks.add(tableBlock(
                        "Financial Message Summary",
                        List.of("Sender", "Message Type", "Currency", "Count", "Total Amount"),
                        summaryRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "Financial Message Detail",
                        List.of("Creation Date", "TRN Reference", "I/O", "Receiver", "Value Date", "Amount", "SN/SEQ", "NACK", "Currency"),
                        data.detailRows.stream()
                                .map(row -> List.of(row.creationDate, row.transactionReference, row.direction, row.receiver, row.valueDate, row.amount, row.sequenceRef, row.nack, row.currency))
                                .toList(),
                        List.of(List.of("Total Currency", blankToDefault(data.totalCurrencyLabel, "-")))
                ));
            }
            case "DUPMSG" -> layout.blocks.add(tableBlock(
                    "Possible Duplicate Messages",
                    List.of("Duplicate Group", "Duplicate Count", "I/O", "Correspondent", "TRN Reference", "Creation", "Verification", "MT/MX", "Currency", "Amount", "Value Date"),
                    data.duplicateRows.stream()
                            .map(row -> List.of(row.duplicateGroupId, String.valueOf(row.duplicateCount), row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate))
                            .toList(),
                    List.of()
            ));
            case "MRM002", "SWM002" -> {
                layout.blocks.add(tableBlock(
                        "Report Summary",
                        List.of("Field", "Value"),
                        data.reportSummaryRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "Department-wise Summary",
                        List.of("Department Name", "Total Transactions", "Successful Transactions", "Failed Transactions", "Pending Transactions", "Repaired Transactions", "Total Debit Amount", "Total Credit Amount", "Net Amount", "Currency"),
                        data.departmentRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "MT Message Breakdown",
                        List.of("MT Message Type", "Message Description", "Department", "Transaction Count", "Success Count", "Failed Count", "Total Amount", "Currency"),
                        data.mtBreakdownRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "MX Message Breakdown",
                        List.of("MX Message Type", "Message Description", "Department", "Transaction Count", "Success Count", "Failed Count", "Total Amount", "Currency"),
                        data.mxBreakdownRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "Currency-wise Summary",
                        List.of("Currency", "Total Transactions", "Debit Count", "Credit Count", "Total Debit Amount", "Total Credit Amount", "Net Amount", "Success Count", "Failed Count"),
                        data.currencySummaryRows,
                        List.of()
                ));
                layout.blocks.add(tableBlock(
                        "Status-wise Summary",
                        List.of("Status", "Total Count", "Percentage", "Total Amount", "Department Count"),
                        data.statusSummaryRows,
                        List.of()
                ));
            }
            case "MRM001" -> {
                layout.blocks.add(textBlock("Between " + prettyMonth(criteria.get("startDate")) + " to " + prettyMonth(criteria.get("endDate"))));
                layout.blocks.add(textBlock("Name of the Bank: " + blankToDefault(stringValue(criteria.get("bankName")), data.primaryBank)));
                layout.blocks.add(tableBlock(
                        "Department Totals",
                        List.of("Department", "Incoming", "Outgoing Excl 950", "Swift 950", "Total"),
                        data.departmentRows,
                        List.of(List.of("TOTAL MESSAGES", String.valueOf(data.totalIncoming), String.valueOf(data.totalOutgoingExcluding950), String.valueOf(data.total950), String.valueOf(data.totalMessages)))
                ));
            }
            case "SWD003", "SWD004", "SWS950" -> layout.blocks.add(tableBlock(
                    "Message Detail",
                    List.of("Creation Date", "Message Reference", "Transaction Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"),
                    data.detailRows.stream()
                            .map(row -> List.of(row.creationDate, row.messageReference, row.transactionReference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency))
                            .toList(),
                    List.of()
            ));
            case "MSGNACK" -> layout.blocks.add(tableBlock(
                    "NACK Messages",
                    List.of("Creation Date", "Message Reference", "Sender", "Receiver", "Type", "Status", "NACK"),
                    data.detailRows.stream()
                            .map(row -> List.of(row.creationDate, row.messageReference, row.sender, row.receiver, row.messageType, row.status, row.nack))
                            .toList(),
                    List.of()
            ));
            case "DBEXT" -> layout.blocks.add(tableBlock(
                    "DB Extract",
                    List.of("Creation Date", "Message Reference", "Transaction Reference", "Direction", "Sender", "Receiver", "Status", "Channel"),
                    data.detailRows.stream()
                            .map(row -> List.of(row.creationDate, row.messageReference, row.transactionReference, row.direction, row.sender, row.receiver, row.status, row.channel))
                            .toList(),
                    List.of()
            ));
            case "IDM001", "IDM002", "IDM003", "IDM004" -> layout.blocks.add(tableBlock(
                    "IDM Detail",
                    List.of("Creation Date", "Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"),
                    data.detailRows.stream()
                            .map(row -> List.of(row.creationDate, row.reference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency))
                            .toList(),
                    List.of()
            ));
            default -> layout.blocks.add(tableBlock(
                    "Detail",
                    List.of("Creation Date", "Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"),
                    data.detailRows.stream()
                            .map(row -> List.of(row.creationDate, row.reference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency))
                            .toList(),
                    List.of()
            ));
        }
        return layout;
    }

    private List<List<String>> buildCriteriaRows(Map<String, Object> criteria) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("From Date", prettyDateTimeValue(criteria.get("startDate")), "To Date", prettyDateTimeValue(criteria.get("endDate"))));
        rows.add(List.of("Sender", blankToDefault(stringValue(criteria.get("sender")), "%"), "Receiver", blankToDefault(stringValue(criteria.get("receiver")), "%")));
        rows.add(List.of("Message Type", blankToDefault(stringValue(criteria.get("messageType")), "Any"), "Message Format", blankToDefault(stringValue(criteria.get("messageFormat")), "Any")));
        rows.add(List.of("Bank Name", blankToDefault(stringValue(criteria.get("bankName")), "Any"), "Direction", blankToDefault(stringValue(criteria.get("direction")), "Any")));
        rows.add(List.of("Department", blankToDefault(stringValue(criteria.get("department")), "All"), "Currency", blankToDefault(stringValue(criteria.get("currency")), "Any")));
        rows.add(List.of("Status", blankToDefault(stringValue(criteria.get("status")), "Any"), "Branch", blankToDefault(stringValue(criteria.get("branch")), "Any")));
        return rows;
    }

    private TextBlock textBlock(String text) {
        return new TextBlock(text);
    }

    private TableBlock tableBlock(String title, List<String> headers, List<List<String>> rows, List<List<String>> footerRows) {
        return new TableBlock(title, headers, rows == null ? List.of() : rows, footerRows == null ? List.of() : footerRows);
    }

    private List<String> pdfLinesForReport(String reportCode, Map<String, Object> criteria, String format, ReportData data) {
        List<String> lines = new ArrayList<>();
        lines.add("SCBD SWIFT SYSTEM");
        lines.add(reportHeader(reportCode));
        lines.add("Run Date: " + prettyInstant(Instant.now()));
        lines.add("From: " + prettyDateTimeValue(criteria.get("startDate")) + "    To: " + prettyDateTimeValue(criteria.get("endDate")));
        switch (blankToDefault(reportCode, "")) {
            case "ISN001" -> {
                lines.add("Logical Terminal: " + blankToDefault(data.primaryLogicalTerminal, "-"));
                lines.add("Last MIR Before Gap | First MIR After the Gap | Missing");
                if (data.gapRows.isEmpty()) {
                    lines.add("No gaps found.");
                } else {
                    data.gapRows.forEach(row -> lines.add(row.leftRef + " | " + row.rightRef + " | " + row.missing));
                }
            }
            case "OSN001" -> {
                lines.add("Logical Terminal: " + blankToDefault(data.primaryLogicalTerminal, "-"));
                lines.add("Last OSN Before Gap | First OSN After the Gap | Missing");
                if (data.gapRows.isEmpty()) {
                    lines.add("No gaps found.");
                } else {
                    data.gapRows.forEach(row -> lines.add(row.leftRef + " | " + row.rightRef + " | " + row.missing));
                }
            }
            case "UTR001" -> {
                lines.add("Sender: " + blankToDefault(stringValue(criteria.get("sender")), "%")
                        + "    Receiver: " + blankToDefault(stringValue(criteria.get("receiver")), "%"));
                lines.add("Summary Table");
                lines.add("Bank | Date | Direction | Message Type | Status | Count");
                if (data.summaryRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.summaryRows.forEach(row -> lines.add(String.join(" | ", row)));
                    if (!data.detailRows.isEmpty()) {
                        lines.add("Detail");
                        DetailRow first = data.detailRows.get(0);
                        lines.add("Bank Name: " + first.bank + " | Message: " + first.messageType + " | Status: " + first.status);
                    }
                }
            }
            case "FINMSG" -> {
                lines.add("Financial Messages: subtotals by Sender, Message Type & Currency");
                if (data.financialSummaryRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.financialSummaryRows.forEach(row ->
                            lines.add("Sender: " + row.sender + " | Message: " + row.messageType + " | Currency: " + row.currency + " | Count: " + row.count + " | Total: " + row.totalAmount)
                    );
                }
                lines.add("Creation Date | TRN Reference | I/O | Receiver | Value Date | Amount | SN/SEQ | Nack | Currency");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(12).forEach(row ->
                            lines.add(String.join(" | ", row.creationDate, row.transactionReference, row.direction, row.receiver, row.valueDate, row.amount, row.sequenceRef, row.nack, row.currency))
                    );
                    lines.add("Total Currency: " + blankToDefault(data.totalCurrencyLabel, "-"));
                }
            }
            case "DUPMSG" -> {
                lines.add("Financial \"Possible Duplicate\" Messages/Emissions");
                lines.add("Sub Format: " + blankToDefault(stringValue(criteria.get("messageFormat")), "Any")
                        + " | Message Type: " + blankToDefault(stringValue(criteria.get("messageType")), "%"));
                lines.add("Group | Dup Count | I/O | Correspon | TRN Refer | Creation | Verification | MT/MX | Ccy | Amount | Value Date");
                if (data.duplicateRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.duplicateRows.stream().limit(20).forEach(row ->
                            lines.add(String.join(" | ", row.duplicateGroupId, String.valueOf(row.duplicateCount), row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate))
                    );
                }
            }
            case "SWD003", "SWD004", "SWS950" -> {
                lines.add("Bank Name: " + blankToDefault(stringValue(criteria.get("bankName")), data.primaryBank));
                lines.add("Creation Date | Message Reference | Transaction Reference | Sender | Receiver | Type | Status | Amount | Currency");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(25).forEach(row ->
                            lines.add(String.join(" | ", row.creationDate, row.messageReference, row.transactionReference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency))
                    );
                }
            }
            case "MSGNACK" -> {
                lines.add("Reports of outgoing nacked messages");
                lines.add("Creation Date | Message Reference | Sender | Receiver | Type | Status | Nack");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(25).forEach(row ->
                            lines.add(String.join(" | ", row.creationDate, row.messageReference, row.sender, row.receiver, row.messageType, row.status, row.nack))
                    );
                }
            }
            case "DBEXT" -> {
                lines.add("DB Extract");
                lines.add("Creation Date | Message Reference | Transaction Reference | Direction | Sender | Receiver | Status | Channel");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(25).forEach(row ->
                            lines.add(String.join(" | ", row.creationDate, row.messageReference, row.transactionReference, row.direction, row.sender, row.receiver, row.status, row.channel))
                    );
                }
            }
            case "MRM001", "MRM002", "SWM002" -> {
                lines.add("Between: " + prettyMonth(criteria.get("startDate")) + "    To: " + prettyMonth(criteria.get("endDate")));
                lines.add("Name of the Bank: " + blankToDefault(stringValue(criteria.get("bankName")), data.primaryBank));
                lines.add("Department | Incoming Message Count Swift | Outgoing Message Count Swift (Excl 950) | Swift (950) | Total");
                if (data.departmentRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.departmentRows.forEach(row -> lines.add(String.join(" | ", row)));
                    lines.add("TOTAL MESSAGES | " + data.totalIncoming + " | " + data.totalOutgoingExcluding950 + " | " + data.total950 + " | " + data.totalMessages);
                }
            }
            case "IDM001", "IDM002", "IDM003", "IDM004" -> {
                criteria.forEach((key, value) -> lines.add(titleize(key) + ": " + Objects.toString(value, "")));
                lines.add("Creation Date | Reference | Sender | Receiver | Type | Status | Amount | Currency");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(25).forEach(row ->
                            lines.add(String.join(" | ", row.creationDate, row.reference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency))
                    );
                }
            }
            default -> criteria.forEach((key, value) -> lines.add(titleize(key) + ": " + Objects.toString(value, "")));
        }
        lines.add("Format: " + format);
        lines.add("END OF REPORT");
        return lines;
    }

    private List<List<String>> excelRowsForReport(String reportCode, Map<String, Object> criteria, ReportData data) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("SCBD SWIFT SYSTEM"));
        rows.add(List.of(reportHeader(reportCode)));
        rows.add(List.of("Run Date", prettyInstant(Instant.now()), "", "", "", "", "", "", "", "Page 1 of 1"));
        rows.add(List.of("Creation Date", prettyDateTimeValue(criteria.get("startDate")) + " - " + prettyDateTimeValue(criteria.get("endDate"))));
        switch (blankToDefault(reportCode, "")) {
            case "DUPMSG" -> {
                rows.add(List.of("Sub Format", blankToDefault(stringValue(criteria.get("messageFormat")), "Any"), "Message Type", blankToDefault(stringValue(criteria.get("messageType")), "%"), "Sender", blankToDefault(stringValue(criteria.get("sender")), ""), "Receiver", blankToDefault(stringValue(criteria.get("receiver")), "")));
                rows.add(List.of("Duplicate Group", "Duplicate Count", "I/O", "Correspon", "TRN Refer", "Creation", "Verification", "MT/MX", "Ccy", "Amount", "Value Date PDC"));
                if (data.duplicateRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.duplicateRows.forEach(row -> rows.add(List.of(row.duplicateGroupId, String.valueOf(row.duplicateCount), row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate)));
                }
            }
            case "UTR001" -> {
                rows.add(List.of("Sender", blankToDefault(stringValue(criteria.get("sender")), "%"), "Receiver", blankToDefault(stringValue(criteria.get("receiver")), "%"), "Sub Format", blankToDefault(stringValue(criteria.get("messageFormat")), "Any"), "Message Type", blankToDefault(stringValue(criteria.get("messageType")), "Any")));
                rows.add(List.of("Bank", "Date", "Direction", "Message Type", "Status", "Count"));
                if (data.summaryRows.isEmpty()) {
                        rows.add(List.of("No records found."));
                } else {
                    rows.addAll(data.summaryRows);
                }
            }
            case "ISN001", "OSN001" -> {
                rows.add(List.of("Logical Terminal", blankToDefault(data.primaryLogicalTerminal, "-")));
                rows.add(List.of("Last MIR/OSN Before Gap", "First MIR/OSN After the Gap", "Missing"));
                if (data.gapRows.isEmpty()) {
                    rows.add(List.of("No gaps found."));
                } else {
                    data.gapRows.forEach(row -> rows.add(List.of(row.leftRef, row.rightRef, row.missing)));
                }
            }
            case "FINMSG" -> {
                rows.add(List.of("Sender", "Message Type", "Currency", "Count", "Total Amount"));
                if (data.financialSummaryRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.financialSummaryRows.forEach(row -> rows.add(List.of(row.sender, row.messageType, row.currency, String.valueOf(row.count), row.totalAmount)));
                }
                rows.add(List.of());
                rows.add(List.of("Creation Date", "TRN Reference", "I/O", "Receiver", "Value Date", "Amount", "SN/SEQ", "Nack", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.creationDate, row.transactionReference, row.direction, row.receiver, row.valueDate, row.amount, row.sequenceRef, row.nack, row.currency)));
                }
            }
            case "MRM001", "MRM002", "SWM002" -> {
                rows.add(List.of("Name of the Bank", blankToDefault(stringValue(criteria.get("bankName")), data.primaryBank)));
                rows.add(List.of("Department", "Incoming Message Count Swift", "Outgoing Message Count Swift (Excl 950)", "Swift (950)", "Total Message Count Swift"));
                if (data.departmentRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    rows.addAll(data.departmentRows);
                    rows.add(List.of("TOTAL MESSAGES", String.valueOf(data.totalIncoming), String.valueOf(data.totalOutgoingExcluding950), String.valueOf(data.total950), String.valueOf(data.totalMessages)));
                }
            }
            case "SWD003", "SWD004", "SWS950" -> {
                rows.add(List.of("Name of the Bank", blankToDefault(stringValue(criteria.get("bankName")), data.primaryBank)));
                rows.add(List.of("Creation Date", "Message Reference", "Transaction Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.creationDate, row.messageReference, row.transactionReference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency)));
                }
            }
            case "MSGNACK" -> {
                rows.add(List.of("Creation Date", "Message Reference", "Sender", "Receiver", "Type", "Status", "Nack"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.creationDate, row.messageReference, row.sender, row.receiver, row.messageType, row.status, row.nack)));
                }
            }
            case "DBEXT" -> {
                rows.add(List.of("Creation Date", "Message Reference", "Transaction Reference", "Direction", "Sender", "Receiver", "Status", "Channel"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.creationDate, row.messageReference, row.transactionReference, row.direction, row.sender, row.receiver, row.status, row.channel)));
                }
            }
            default -> {
                criteria.forEach((key, value) -> rows.add(List.of(titleize(key), Objects.toString(value, ""))));
                rows.add(List.of("Creation Date", "Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.creationDate, row.reference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency)));
                }
            }
        }
        rows.add(List.of("END OF REPORT"));
        return rows;
    }

    private List<String> csvRowsForReport(String reportCode, Map<String, Object> criteria, ReportData data) {
        List<String> rows = new ArrayList<>();
        switch (blankToDefault(reportCode, "")) {
            case "DUPMSG" -> {
                rows.add("\"Duplicate Group\",\"Duplicate Count\",\"I/O\",\"Correspon\",\"TRN Refer\",\"Creation\",\"Verification\",\"MT/MX\",\"Ccy\",\"Amount\",\"Value Date PDC\"");
                if (data.duplicateRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.duplicateRows.forEach(row -> rows.add(csvLine(row.duplicateGroupId, String.valueOf(row.duplicateCount), row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate)));
                }
            }
            case "UTR001" -> {
                rows.add("\"Bank\",\"Date\",\"Direction\",\"Message Type\",\"Status\",\"Count\"");
                if (data.summaryRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.summaryRows.forEach(row -> rows.add(csvLine(row.toArray(String[]::new))));
                }
            }
            case "ISN001", "OSN001" -> {
                rows.add(csvLine("Last MIR/OSN Before Gap", "First MIR/OSN After the Gap", "Missing"));
                if (data.gapRows.isEmpty()) {
                    rows.add("\"No gaps found\"");
                } else {
                    data.gapRows.forEach(row -> rows.add(csvLine(row.leftRef, row.rightRef, row.missing)));
                }
            }
            case "FINMSG" -> {
                rows.add(csvLine("Sender", "Message Type", "Currency", "Count", "Total Amount"));
                if (data.financialSummaryRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.financialSummaryRows.forEach(row -> rows.add(csvLine(row.sender, row.messageType, row.currency, String.valueOf(row.count), row.totalAmount)));
                }
                rows.add("");
                rows.add(csvLine("Creation Date", "TRN Reference", "I/O", "Receiver", "Value Date", "Amount", "SN/SEQ", "Nack", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.creationDate, row.transactionReference, row.direction, row.receiver, row.valueDate, row.amount, row.sequenceRef, row.nack, row.currency)));
                }
            }
            case "MRM001", "MRM002", "SWM002" -> {
                rows.add("\"Department\",\"Incoming\",\"Outgoing Excl 950\",\"Swift 950\",\"Total\"");
                if (data.departmentRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.departmentRows.forEach(row -> rows.add(csvLine(row.toArray(String[]::new))));
                    rows.add(csvLine("TOTAL MESSAGES", String.valueOf(data.totalIncoming), String.valueOf(data.totalOutgoingExcluding950), String.valueOf(data.total950), String.valueOf(data.totalMessages)));
                }
            }
            case "SWD003", "SWD004", "SWS950" -> {
                rows.add(csvLine("Creation Date", "Message Reference", "Transaction Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.creationDate, row.messageReference, row.transactionReference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency)));
                }
            }
            case "MSGNACK" -> {
                rows.add(csvLine("Creation Date", "Message Reference", "Sender", "Receiver", "Type", "Status", "Nack"));
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.creationDate, row.messageReference, row.sender, row.receiver, row.messageType, row.status, row.nack)));
                }
            }
            case "DBEXT" -> {
                rows.add(csvLine("Creation Date", "Message Reference", "Transaction Reference", "Direction", "Sender", "Receiver", "Status", "Channel"));
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.creationDate, row.messageReference, row.transactionReference, row.direction, row.sender, row.receiver, row.status, row.channel)));
                }
            }
            default -> {
                criteria.forEach((key, value) ->
                        rows.add("\"" + escapeCsv(titleize(key)) + "\",\"" + escapeCsv(Objects.toString(value, "")) + "\""));
                rows.add(csvLine("Creation Date", "Reference", "Sender", "Receiver", "Type", "Status", "Amount", "Currency"));
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.creationDate, row.reference, row.sender, row.receiver, row.messageType, row.status, row.amount, row.currency)));
                }
            }
        }
        return rows;
    }

    private String csvLine(String... values) {
        return "\"" + String.join("\",\"", List.of(values).stream().map(this::escapeCsv).toList()) + "\"";
    }

    private ReportLayout buildDynamicReportLayout(Map<String, Object> criteria) {
        Document definition = findReportDefinition(stringValue(criteria.get("reportCode")));
        if (definition == null) {
            return null;
        }
        String reportCode = blankToDefault(definition.getString("reportCode"), blankToDefault(stringValue(criteria.get("reportCode")), "REPORT"));
        Map<String, String> collectionAliases = reportCollectionAliases(definition);
        String collectionAlias = blankToDefault(definition.getString("collection"), "messages");
        String collectionName = resolveReportCollection(collectionAlias, collectionAliases);
        Document match = buildDefinitionMatch(definition, criteria);
        List<Document> commonStages = documentList(definition.get("commonStages"));
        long totalCount = mongoTemplate.getCollection(collectionName).countDocuments(match);

        ReportLayout layout = new ReportLayout();
        layout.reportCode = reportCode;
        layout.systemTitle = definitionSystemTitle(definition);
        layout.reportTitle = blankToDefault(definition.getString("reportName"), reportHeader(reportCode));
        layout.runDate = prettyInstant(Instant.now());
        layout.criteriaRows = buildCriteriaRows(criteria);
        layout.totalMessageCount = (int) Math.min(totalCount, Integer.MAX_VALUE);

        for (Document section : documentList(definition.get("sections"))) {
            String title = blankToDefault(section.getString("title"), "Report Section");
            String type = blankToDefault(section.getString("type"), "table");
            String sectionCollection = resolveReportCollection(blankToDefault(section.getString("collection"), collectionAlias), collectionAliases);
            List<Document> result = executeDefinitionSection(sectionCollection, match, commonStages, section, collectionAliases);
            if ("keyValue".equalsIgnoreCase(type)) {
                layout.blocks.add(tableBlock(title, List.of("Field", "Value"), keyValueRows(section, result, criteria), List.of()));
            } else {
                List<String> columns = stringList(section.get("columns"));
                layout.blocks.add(tableBlock(title, columns, tableRows(section, result, columns, criteria, layout.totalMessageCount), List.of()));
            }
        }
        return layout;
    }

    private IllegalArgumentException missingReportDefinition(Map<String, Object> criteria) {
        String reportCode = blankToDefault(stringValue(criteria.get("reportCode")), "REPORT");
        return new IllegalArgumentException("No report template found in "
                + appConfig.getReportDefinitionsCollection()
                + " for report code: " + reportCode);
    }

    private Document findReportDefinition(String reportCode) {
        if (reportCode == null || reportCode.isBlank()) {
            return null;
        }
        return mongoTemplate.getCollection(appConfig.getReportDefinitionsCollection())
                .find(new Document("reportCode", reportCode).append("enabled", new Document("$ne", false)))
                .first();
    }

    private String definitionSystemTitle(Document definition) {
        Document layout = documentAt(definition, "layout");
        return blankToDefault(layout.getString("systemTitle"), "SCBD SWIFT SYSTEM");
    }

    private List<Document> executeDefinitionSection(String collectionName,
                                                    Document match,
                                                    List<Document> commonStages,
                                                    Document section,
                                                    Map<String, String> collectionAliases) {
        List<Document> pipeline = new ArrayList<>();
        if (match != null && !match.isEmpty()) {
            pipeline.add(new Document("$match", cloneDocument(match)));
        }
        pipeline.addAll(resolvePipelineCollections(cloneDocuments(commonStages), collectionAliases));
        pipeline.addAll(resolvePipelineCollections(cloneDocuments(documentList(section.get("pipeline"))), collectionAliases));
        if (pipeline.isEmpty()) {
            return List.of();
        }
        return mongoTemplate.getCollection(collectionName)
                .aggregate(pipeline)
                .allowDiskUse(true)
                .into(new ArrayList<>());
    }

    private Map<String, String> reportCollectionAliases(Document definition) {
        Map<String, String> aliases = new LinkedHashMap<>();
        putCollectionAlias(aliases, "messages", appConfig.getSwiftCollection());
        putCollectionAlias(aliases, "message", appConfig.getSwiftCollection());
        putCollectionAlias(aliases, "swift", appConfig.getSwiftCollection());
        putCollectionAlias(aliases, "amp_messages", appConfig.getSwiftCollection());

        putCollectionAlias(aliases, "payloads", appConfig.getPayloadsCollection());
        putCollectionAlias(aliases, "payload", appConfig.getPayloadsCollection());
        putCollectionAlias(aliases, "amp_payloads", appConfig.getPayloadsCollection());

        putCollectionAlias(aliases, "rawCopies", appConfig.getRawCopiesCollection());
        putCollectionAlias(aliases, "rawcopies", appConfig.getRawCopiesCollection());
        putCollectionAlias(aliases, "raw_copies", appConfig.getRawCopiesCollection());
        putCollectionAlias(aliases, "amp_raw_copies", appConfig.getRawCopiesCollection());

        putCollectionAlias(aliases, "users", appConfig.getUsersCollection());
        putCollectionAlias(aliases, "audit", appConfig.getAuditCollection());
        putCollectionAlias(aliases, "failures", appConfig.getFailuresCollection());
        putCollectionAlias(aliases, "dropdownOptions", appConfig.getDropdownOptionsCollection());
        putCollectionAlias(aliases, "reportTemplates", appConfig.getReportTemplatesCollection());
        putCollectionAlias(aliases, "reportDefinitions", appConfig.getReportDefinitionsCollection());

        Document configured = documentAt(definition, "collections");
        configured.forEach((alias, value) ->
                putCollectionAlias(aliases, alias, resolveReportCollection(Objects.toString(value, ""), aliases)));
        return aliases;
    }

    private void putCollectionAlias(Map<String, String> aliases, String alias, String collectionName) {
        String normalizedAlias = normalizeCollectionAlias(alias);
        String normalizedCollection = normalizeCollectionAlias(collectionName);
        if (!normalizedAlias.isBlank() && collectionName != null && !collectionName.isBlank()) {
            aliases.put(normalizedAlias, collectionName);
        }
        if (!normalizedCollection.isBlank() && collectionName != null && !collectionName.isBlank()) {
            aliases.putIfAbsent(normalizedCollection, collectionName);
        }
    }

    private String resolveReportCollection(String collectionAlias, Map<String, String> aliases) {
        String requested = blankToDefault(collectionAlias, "messages");
        String normalized = normalizeCollectionAlias(requested);
        return aliases.getOrDefault(normalized, requested);
    }

    private String normalizeCollectionAlias(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1);
        }
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private List<Document> resolvePipelineCollections(List<Document> pipeline, Map<String, String> collectionAliases) {
        return pipeline.stream()
                .map(stage -> resolvePipelineCollections(stage, collectionAliases))
                .toList();
    }

    private Document resolvePipelineCollections(Document stage, Map<String, String> collectionAliases) {
        Document resolved = cloneDocument(stage);
        rewriteCollectionReferences(resolved, collectionAliases);
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private void rewriteCollectionReferences(Object value, Map<String, String> collectionAliases) {
        if (value instanceof Document document) {
            Object lookup = document.get("$lookup");
            if (lookup instanceof Document lookupDocument && lookupDocument.get("from") instanceof String from) {
                lookupDocument.put("from", resolveReportCollection(from, collectionAliases));
            }
            Object unionWith = document.get("$unionWith");
            if (unionWith instanceof String unionCollection) {
                document.put("$unionWith", resolveReportCollection(unionCollection, collectionAliases));
            } else if (unionWith instanceof Document unionDocument && unionDocument.get("coll") instanceof String coll) {
                unionDocument.put("coll", resolveReportCollection(coll, collectionAliases));
            }
            document.values().forEach(next -> rewriteCollectionReferences(next, collectionAliases));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> rewriteCollectionReferences(item, collectionAliases));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(next -> rewriteCollectionReferences(next, collectionAliases));
        }
    }

    private Document buildDefinitionMatch(Document definition, Map<String, Object> criteria) {
        List<Document> andConditions = new ArrayList<>();
        Document baseMatch = documentAt(definition, "baseMatch");
        if (!baseMatch.isEmpty()) {
            andConditions.add(cloneDocument(baseMatch));
        }
        for (Document filter : documentList(definition.get("filters"))) {
            Object rawValue = criteriaValue(criteria, filter.getString("name"));
            String value = stringValue(rawValue);
            if (isEmptyReportFilter(value)) {
                continue;
            }
            Document condition = buildDefinitionFilter(filter, value);
            if (condition != null && !condition.isEmpty()) {
                andConditions.add(condition);
            }
        }
        if (andConditions.isEmpty()) {
            return new Document();
        }
        if (andConditions.size() == 1) {
            return andConditions.get(0);
        }
        return new Document("$and", andConditions);
    }

    private Object criteriaValue(Map<String, Object> criteria, String name) {
        if (criteria == null || name == null) {
            return null;
        }
        Object value = criteria.get(name);
        if (value != null) {
            return value;
        }
        if ("fromDate".equals(name)) {
            return criteria.get("startDate");
        }
        if ("toDate".equals(name)) {
            return criteria.get("endDate");
        }
        return null;
    }

    private Document buildDefinitionFilter(Document filter, String value) {
        String operator = blankToDefault(filter.getString("operator"), "$regex");
        String dbPath = filter.getString("dbPath");
        List<String> dbPaths = stringList(filter.get("dbPaths"));
        if (dbPaths.isEmpty() && dbPath != null && !dbPath.isBlank()) {
            dbPaths = List.of(dbPath);
        }
        if (dbPaths.isEmpty()) {
            return new Document();
        }
        return switch (operator) {
            case "$gte", "$lte" -> dateFilter(dbPaths.get(0), operator, value);
            case "$eq" -> equalityFilter(dbPaths.get(0), filter.getString("name"), value);
            case "$direction" -> directionDefinitionFilter(dbPaths, value);
            default -> regexDefinitionFilter(dbPaths, value);
        };
    }

    private Document dateFilter(String dbPath, String operator, String value) {
        Instant instant = parseInstant(value);
        String normalized = instant == null ? value : instant.toString();
        return new Document(dbPath, new Document(operator, normalized));
    }

    private Document equalityFilter(String dbPath, String name, String value) {
        if ("messageFormat".equals(name)) {
            String normalized = value.toUpperCase(Locale.ROOT);
            if (normalized.contains("MT & MX") || normalized.equals("ALL")) {
                return new Document();
            }
            if (normalized.contains("MT")) {
                return new Document(dbPath, "MT");
            }
            if (normalized.contains("MX")) {
                return new Document(dbPath, "MX");
            }
        }
        return new Document(dbPath, value);
    }

    private Document regexDefinitionFilter(List<String> dbPaths, String value) {
        if (dbPaths.size() == 1) {
            return new Document(dbPaths.get(0), new Document("$regex", value).append("$options", "i"));
        }
        return new Document("$or", dbPaths.stream()
                .map(path -> new Document(path, new Document("$regex", value).append("$options", "i")))
                .toList());
    }

    private Document directionDefinitionFilter(List<String> dbPaths, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("in")) {
            return directionOr(dbPaths, "I", "Inbound");
        }
        if (normalized.startsWith("out")) {
            return directionOr(dbPaths, "O", "Outbound");
        }
        return new Document();
    }

    private Document directionOr(List<String> dbPaths, String finValue, String textValue) {
        List<Document> conditions = new ArrayList<>();
        for (String path : dbPaths) {
            if (path.contains("DirectionID")) {
                conditions.add(new Document(path, finValue));
            } else {
                conditions.add(new Document(path, new Document("$regex", textValue).append("$options", "i")));
            }
        }
        return new Document("$or", conditions);
    }

    private List<List<String>> keyValueRows(Document section, List<Document> result, Map<String, Object> criteria) {
        if (result.isEmpty()) {
            return List.of();
        }
        Document row = result.get(0);
        List<List<String>> rows = new ArrayList<>();
        List<Document> fields = documentList(section.get("fields"));
        if (!fields.isEmpty()) {
            for (Document field : fields) {
                String label = blankToDefault(field.getString("label"), blankToDefault(field.getString("field"), "-"));
                String fieldName = blankToDefault(field.getString("field"), label);
                rows.add(List.of(label, formatDynamicValue(row.get(fieldName), label, field.getString("format"), criteria, 0)));
            }
            return rows;
        }
        row.forEach((key, value) -> rows.add(List.of(key, formatDynamicValue(value, key, null, criteria, 0))));
        return rows;
    }

    private List<List<String>> tableRows(Document section,
                                         List<Document> result,
                                         List<String> columns,
                                         Map<String, Object> criteria,
                                         int totalMessageCount) {
        List<Document> rows = result;
        List<String> fixedStatuses = stringList(section.get("fixedStatusRows"));
        if (!fixedStatuses.isEmpty()) {
            rows = fixedStatusDocuments(result, fixedStatuses);
        }
        List<List<String>> tableRows = new ArrayList<>();
        for (Document document : rows) {
            List<String> row = new ArrayList<>();
            for (String column : columns) {
                row.add(formatDynamicValue(document.get(column), column, null, criteria, totalMessageCount));
            }
            tableRows.add(row);
        }
        return tableRows;
    }

    private List<Document> fixedStatusDocuments(List<Document> result, List<String> fixedStatuses) {
        Map<String, Document> byStatus = new LinkedHashMap<>();
        for (Document row : result) {
            byStatus.put(blankToDefault(row.getString("Status"), "-"), row);
        }
        List<Document> rows = new ArrayList<>();
        for (String status : fixedStatuses) {
            rows.add(byStatus.getOrDefault(status, new Document("Status", status)
                    .append("Total Count", 0)
                    .append("Percentage", 0)
                    .append("Total Amount", 0)
                    .append("Department Count", 0)));
        }
        return rows;
    }

    private String formatDynamicValue(Object value,
                                      String fieldName,
                                      String explicitFormat,
                                      Map<String, Object> criteria,
                                      int totalMessageCount) {
        Object resolved = resolveDynamicPlaceholder(value, criteria);
        if (resolved == null) {
            return "-";
        }
        String field = blankToDefault(fieldName, "").toLowerCase(Locale.ROOT);
        if ("amount".equalsIgnoreCase(explicitFormat) || field.contains("amount")) {
            return formatAmount(numberValue(resolved));
        }
        if (field.contains("percentage")) {
            double base = numberValue(resolved);
            double percentage = totalMessageCount <= 0 ? 0d : base * 100d / totalMessageCount;
            return String.format(Locale.US, "%.2f%%", percentage);
        }
        if (resolved instanceof Number number && (field.contains("count") || field.contains("transaction") || field.contains("department"))) {
            return formatCount(number.longValue());
        }
        return Objects.toString(resolved, "-");
    }

    private Object resolveDynamicPlaceholder(Object value, Map<String, Object> criteria) {
        if (!(value instanceof String text) || !text.startsWith("{{") || !text.endsWith("}}")) {
            return value;
        }
        String key = text.substring(2, text.length() - 2);
        return switch (key) {
            case "reportMonth" -> prettyMonth(criteria.get("startDate"));
            case "fromDateDisplay" -> prettyDateTimeValue(criteria.get("startDate"));
            case "toDateDisplay" -> prettyDateTimeValue(criteria.get("endDate"));
            case "generatedDateTime" -> prettyInstant(Instant.now());
            case "generatedBy" -> blankToDefault(stringValue(criteria.get("generatedBy")), DEFAULT_USER);
            default -> text;
        };
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(Objects.toString(value, "0").replace(",", ""));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private boolean isEmptyReportFilter(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "any".equals(normalized) || normalized.startsWith("all");
    }

    private List<Document> cloneDocuments(List<Document> documents) {
        return documents.stream().map(this::cloneDocument).toList();
    }

    private Document cloneDocument(Document document) {
        return document == null ? new Document() : Document.parse(document.toJson());
    }

    @SuppressWarnings("unchecked")
    private List<Document> documentList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Document document) {
                documents.add(document);
            } else if (item instanceof Map<?, ?> map) {
                documents.add(new Document((Map<String, Object>) map));
            }
        }
        return documents;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> Objects.toString(item, ""))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private ReportData buildReportData(Map<String, Object> criteria) {
        List<Document> documents = queryMessages(criteria);
        String reportCode = blankToDefault(stringValue(criteria.get("reportCode")), "SWD003");
        ReportData data = new ReportData();
        data.detailRows = buildDetailRows(documents);
        data.primaryBank = data.detailRows.stream().map(row -> row.bank).filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst().orElse("-");
        data.primaryLogicalTerminal = data.detailRows.stream().map(row -> row.logicalTerminal).filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst().orElse("-");
        data.totalMessages = data.detailRows.size();
        data.totalCurrencyLabel = summarizeCurrency(data.detailRows);
        switch (reportCode) {
            case "UTR001" -> data.summaryRows = buildUserTrafficSummary(data.detailRows);
            case "MRM001" -> populateDepartmentSummary(data);
            case "MRM002", "SWM002" -> populateMrm002Sections(criteria, data);
            case "ISN001", "OSN001" -> data.gapRows = buildGapRows(data.detailRows);
            case "FINMSG" -> data.financialSummaryRows = buildFinancialSummary(data.detailRows);
            case "DUPMSG" -> data.duplicateRows = buildDuplicateRows(data.detailRows);
            default -> {
                data.summaryRows = List.of();
                data.departmentRows = List.of();
                data.gapRows = List.of();
                data.financialSummaryRows = List.of();
                data.duplicateRows = List.of();
            }
        }
        return data;
    }

    private List<Document> queryMessages(Map<String, Object> criteria) {
        Document match = reportQueryRegistry.buildMessageMatch(criteria);
        return mongoTemplate.getCollection(appConfig.getSwiftCollection())
                .find(match)
                .limit(5000)
                .into(new ArrayList<>());
    }

    private List<DetailRow> buildDetailRows(List<Document> documents) {
        List<DetailRow> rows = new ArrayList<>();
        for (Document doc : documents) {
            Document header = documentAt(doc, "header");
            Document status = documentAt(doc, "status");
            Document extracted = documentAt(doc, "extractedFields");
            Document protocolParams = documentAt(doc, "protocolParams");
            Document fin = documentAt(protocolParams, "FIN");
            Document block1 = documentAt(fin, "BasicHeaderBlock");
            Document block2 = documentAt(fin, "ApplicationHeaderBlock");
            DetailRow row = new DetailRow();
            row.bank = firstNonBlank(header.getString("owner"), header.getString("senderAddress"), block1.getString("LogicalTerminalAddress"));
            row.date = dateOnly(header.getString("dateCreated"));
            row.creationDate = prettyDateTimeValue(header.get("dateCreated"));
            row.direction = mapDirection(firstNonBlank(block2.getString("DirectionID"), header.getString("direction")));
            row.messageType = firstNonBlank(header.getString("messageTypeCode"), doc.getString("messageType"), doc.getString("messageFamily"));
            row.status = firstNonBlank(status.getString("reason"), status.getString("current"), "-");
            row.count = "1";
            row.reference = firstNonBlank(header.getString("transactionReference"), header.getString("messageReference"), doc.getString("messageReference"), "-");
            row.messageReference = firstNonBlank(header.getString("messageReference"), doc.getString("messageReference"), "-");
            row.transactionReference = firstNonBlank(header.getString("transactionReference"), "-");
            row.receiver = firstNonBlank(header.getString("receiverAddress"), block2.getString("ReceiversAddress"), "-");
            row.sender = firstNonBlank(header.getString("senderAddress"), block1.getString("LogicalTerminalAddress"), "-");
            row.valueDate = firstNonBlank(extracted.getString("valueDate"), "-");
            row.amount = firstNonBlank(extracted.getString("amount"), "-");
            row.currency = firstNonBlank(extracted.getString("currency"), "-");
            row.sequenceRef = sequenceRef(protocolParams, block1);
            row.nack = containsNack(status) ? "Y" : "-";
            row.logicalTerminal = firstNonBlank(block1.getString("LogicalTerminalAddress"), protocolParams.getString("logicalTerminal"), header.getString("senderAddress"));
            row.correspondent = firstNonBlank(header.getString("senderAddress"), "-");
            row.verification = firstNonBlank(status.getString("reason"), "-");
            row.userIssuedAsPdc = "-";
            row.department = firstNonBlank(header.getString("owner"), "None");
            row.channel = firstNonBlank(header.getString("backendChannel"), header.getString("networkChannel"), "-");
            row.sessionNumber = firstNonBlank(protocolParams.getString("sessionNumber"), block1.getString("SessionNumber"), "-");
            row.sequenceNumber = firstNonBlank(protocolParams.getString("sequenceNumber"), block1.getString("SequenceNumber"), "-");
            row.rawPayload = documentAt(doc, "body").getString("rawPayload");
            row.uetr = extractUetr(doc, row.rawPayload);
            rows.add(row);
        }
        return rows;
    }

    private List<List<String>> buildUserTrafficSummary(List<DetailRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            String key = String.join("|", blankToDefault(row.bank, "-"), blankToDefault(row.date, "-"), blankToDefault(row.direction, "-"), blankToDefault(row.messageType, "-"), blankToDefault(row.status, "-"));
            counts.merge(key, 1, Integer::sum);
        }
        List<List<String>> summary = new ArrayList<>();
        counts.forEach((key, count) -> {
            String[] parts = key.split("\\|", -1);
            summary.add(List.of(parts[0], parts[1], parts[2], parts[3], parts[4], String.valueOf(count)));
        });
        return summary;
    }

    private List<FinancialSummaryRow> buildFinancialSummary(List<DetailRow> rows) {
        Map<String, FinancialSummaryRow> groups = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            String sender = blankToDefault(row.sender, "-");
            String messageType = blankToDefault(row.messageType, "-");
            String currency = blankToDefault(row.currency, "-");
            String key = String.join("|", sender, messageType, currency);
            FinancialSummaryRow summary = groups.computeIfAbsent(key, ignored -> {
                FinancialSummaryRow created = new FinancialSummaryRow();
                created.sender = sender;
                created.messageType = messageType;
                created.currency = currency;
                created.totalAmount = "0.00";
                return created;
            });
            summary.count++;
            try {
                double next = Double.parseDouble(blankToDefault(summary.totalAmount, "0").replace(",", "")) + Double.parseDouble(blankToDefault(row.amount, "0").replace(",", ""));
                summary.totalAmount = String.format(Locale.US, "%.2f", next);
            } catch (Exception ignore) {
                summary.totalAmount = blankToDefault(row.amount, "-");
            }
        }
        return new ArrayList<>(groups.values());
    }

    private List<DetailRow> buildDuplicateRows(List<DetailRow> rows) {
        Map<String, List<DetailRow>> grouped = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            String key = duplicateKey(row);
            if (key == null) continue;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<DetailRow> duplicates = new ArrayList<>();
        int groupCounter = 1;
        for (Map.Entry<String, List<DetailRow>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String groupId = "DUP-" + String.format(Locale.US, "%03d", groupCounter++);
            for (DetailRow row : entry.getValue()) {
                row.duplicateGroupId = groupId;
                row.duplicateCount = entry.getValue().size();
                duplicates.add(row);
            }
        }
        return duplicates;
    }

    private String duplicateKey(DetailRow row) {
        if (row == null) return null;
        if (row.uetr != null && !row.uetr.isBlank() && !"-".equals(row.uetr)) {
            return "UETR|" + row.uetr;
        }
        String compound = String.join("|",
                blankToDefault(row.transactionReference, blankToDefault(row.messageReference, "-")),
                blankToDefault(row.amount, "-"),
                blankToDefault(row.currency, "-"),
                blankToDefault(row.sender, "-"),
                blankToDefault(row.receiver, "-"),
                blankToDefault(row.valueDate, "-"));
        if (!compound.contains("|-|-")) {
            return "TRN|" + compound;
        }
        if (row.rawPayload != null && !row.rawPayload.isBlank()) {
            return "RAW|" + Integer.toHexString(row.rawPayload.hashCode());
        }
        return null;
    }

    private void populateDepartmentSummary(ReportData data) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (DetailRow row : data.detailRows) {
            int[] totals = counts.computeIfAbsent(blankToDefault(row.department, "None"), k -> new int[4]);
            boolean mt950 = row.messageType != null && (row.messageType.contains("950") || row.messageType.contains("940"));
            boolean incoming = "Inbound".equalsIgnoreCase(row.direction) || "Incoming".equalsIgnoreCase(row.direction) || "I".equalsIgnoreCase(row.direction);
            if (incoming) {
                totals[0]++;
                data.totalIncoming++;
            } else if (mt950) {
                totals[2]++;
                data.total950++;
            } else {
                totals[1]++;
                data.totalOutgoingExcluding950++;
            }
            totals[3]++;
        }
        List<List<String>> rows = new ArrayList<>();
        counts.forEach((department, total) -> rows.add(List.of(department, String.valueOf(total[0]), String.valueOf(total[1]), String.valueOf(total[2]), String.valueOf(total[3]))));
        data.departmentRows = rows;
    }

    private void populateMrm002Sections(Map<String, Object> criteria, ReportData data) {
        Map<String, Mrm002Group> departmentGroups = new LinkedHashMap<>();
        Map<String, Mrm002TypeGroup> mtGroups = new LinkedHashMap<>();
        Map<String, Mrm002TypeGroup> mxGroups = new LinkedHashMap<>();
        Map<String, Mrm002CurrencyGroup> currencyGroups = new LinkedHashMap<>();
        Map<String, Mrm002StatusGroup> statusGroups = new LinkedHashMap<>();

        for (DetailRow row : data.detailRows) {
            String department = blankToDefault(row.department, "None");
            String currency = blankToDefault(row.currency, "N/A");
            String status = statusBucket(row.status);
            double amount = parseAmount(row.amount);
            boolean debit = isDebit(row.direction);
            boolean mt = isMtMessage(row);

            Mrm002Group departmentGroup = departmentGroups.computeIfAbsent(department, Mrm002Group::new);
            departmentGroup.accept(status, amount, debit, currency);

            String typeKey = String.join("|", blankToDefault(row.messageType, "-"), department, currency);
            Mrm002TypeGroup typeGroup = (mt ? mtGroups : mxGroups).computeIfAbsent(typeKey,
                    ignored -> new Mrm002TypeGroup(blankToDefault(row.messageType, "-"), messageDescription(row.messageType), department, currency));
            typeGroup.accept(status, amount);

            Mrm002CurrencyGroup currencyGroup = currencyGroups.computeIfAbsent(currency, Mrm002CurrencyGroup::new);
            currencyGroup.accept(status, amount, debit);

            Mrm002StatusGroup statusGroup = statusGroups.computeIfAbsent(status, Mrm002StatusGroup::new);
            statusGroup.accept(amount, department);
        }

        double grandTotal = data.detailRows.stream().mapToDouble(row -> parseAmount(row.amount)).sum();
        long successful = data.detailRows.stream().filter(row -> "Success".equals(statusBucket(row.status))).count();
        long failed = data.detailRows.stream().filter(row -> "Failed".equals(statusBucket(row.status)) || "Rejected".equals(statusBucket(row.status))).count();
        long pending = data.detailRows.stream().filter(row -> "Pending".equals(statusBucket(row.status))).count();
        String currencyLabel = currencyGroups.size() == 1 ? currencyGroups.keySet().iterator().next() : "Multiple";

        data.reportSummaryRows = List.of(
                List.of("Report Name", reportHeader(blankToDefault(stringValue(criteria.get("reportCode")), "MRM002"))),
                List.of("Report Code", blankToDefault(stringValue(criteria.get("reportCode")), "MRM002")),
                List.of("Report Month", prettyMonth(criteria.get("startDate"))),
                List.of("From Date", prettyDateTimeValue(criteria.get("startDate"))),
                List.of("To Date", prettyDateTimeValue(criteria.get("endDate"))),
                List.of("Generated Date & Time", prettyInstant(Instant.now())),
                List.of("Generated By", blankToDefault(stringValue(criteria.get("generatedBy")), DEFAULT_USER)),
                List.of("Total Departments", formatCount(departmentGroups.size())),
                List.of("Total Transactions", formatCount(data.totalMessages)),
                List.of("Total Successful Transactions", formatCount(successful)),
                List.of("Total Failed Transactions", formatCount(failed)),
                List.of("Total Pending Transactions", formatCount(pending)),
                List.of("Grand Total Amount", formatAmount(grandTotal)),
                List.of("Currency", currencyLabel)
        );

        data.departmentRows = departmentGroups.values().stream().map(Mrm002Group::toRow).toList();
        data.mtBreakdownRows = mtGroups.values().stream().map(Mrm002TypeGroup::toRow).toList();
        data.mxBreakdownRows = mxGroups.values().stream().map(Mrm002TypeGroup::toRow).toList();
        data.currencySummaryRows = currencyGroups.values().stream().map(Mrm002CurrencyGroup::toRow).toList();

        long total = Math.max(data.totalMessages, 1);
        List<String> orderedStatuses = List.of("Success", "Failed", "Pending", "Repaired", "Rejected", "Cancelled");
        List<List<String>> statusRows = new ArrayList<>();
        for (String status : orderedStatuses) {
            Mrm002StatusGroup group = statusGroups.getOrDefault(status, new Mrm002StatusGroup(status));
            statusRows.add(group.toRow(total));
        }
        statusGroups.forEach((status, group) -> {
            if (!orderedStatuses.contains(status)) {
                statusRows.add(group.toRow(total));
            }
        });
        data.statusSummaryRows = statusRows;
    }

    private List<GapRow> buildGapRows(List<DetailRow> rows) {
        List<DetailRow> filtered = rows.stream()
                .filter(row -> row.sequenceNumber != null && !row.sequenceNumber.isBlank() && !"-".equals(row.sequenceNumber))
                .sorted(Comparator.comparing((DetailRow row) -> blankToDefault(row.logicalTerminal, ""))
                        .thenComparing(row -> blankToDefault(row.sessionNumber, ""))
                        .thenComparingInt(row -> parseSequence(blankToDefault(row.sequenceNumber, "")) == null ? Integer.MIN_VALUE : parseSequence(blankToDefault(row.sequenceNumber, ""))))
                .toList();
        List<GapRow> gaps = new ArrayList<>();
        for (int i = 1; i < filtered.size(); i++) {
            DetailRow previous = filtered.get(i - 1);
            DetailRow current = filtered.get(i);
            if (!Objects.equals(previous.logicalTerminal, current.logicalTerminal)) continue;
            if (!Objects.equals(previous.sessionNumber, current.sessionNumber)) continue;
            Integer prev = parseSequence(previous.sequenceNumber);
            Integer next = parseSequence(current.sequenceNumber);
            if (prev == null || next == null || next - prev <= 1) continue;
            GapRow gap = new GapRow();
            gap.leftRef = blankToDefault(previous.sessionNumber, "-") + "/" + blankToDefault(previous.sequenceNumber, "-");
            gap.rightRef = blankToDefault(current.sessionNumber, "-") + "/" + blankToDefault(current.sequenceNumber, "-");
            gap.missing = String.valueOf(next - prev - 1);
            gaps.add(gap);
        }
        return gaps;
    }

    private Integer parseSequence(String sequenceRef) {
        if (sequenceRef == null) return null;
        String[] parts = sequenceRef.split("/");
        String value = parts.length > 1 ? parts[1] : parts[0];
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception ignore) {
            return null;
        }
    }

    private String summarizeCurrency(List<DetailRow> rows) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            if (row.currency == null || row.currency.equals("-")) continue;
            try {
                totals.merge(row.currency, Double.parseDouble(row.amount.replace(",", "")), Double::sum);
            } catch (Exception ignore) {
                totals.putIfAbsent(row.currency, 0d);
            }
        }
        return totals.isEmpty()
                ? "-"
                : totals.entrySet().stream().findFirst().map(e -> e.getKey() + String.format(Locale.US, "%.2f", e.getValue())).orElse("-");
    }

    private boolean isMrm002Report(String reportCode) {
        return "MRM002".equalsIgnoreCase(blankToDefault(reportCode, "")) || "SWM002".equalsIgnoreCase(blankToDefault(reportCode, ""));
    }

    private boolean isMtMessage(DetailRow row) {
        String value = blankToDefault(row.messageType, "").toUpperCase(Locale.ROOT);
        return value.startsWith("MT") || value.matches("\\d{3}");
    }

    private boolean isDebit(String direction) {
        String value = blankToDefault(direction, "").toLowerCase(Locale.ROOT);
        return value.startsWith("out") || "o".equals(value);
    }

    private String statusBucket(String raw) {
        String value = blankToDefault(raw, "").toLowerCase(Locale.ROOT);
        if (value.contains("reject")) return "Rejected";
        if (value.contains("cancel")) return "Cancelled";
        if (value.contains("repair")) return "Repaired";
        if (value.contains("pend") || value.contains("wait") || value.contains("progress")) return "Pending";
        if (value.contains("fail") || value.contains("nack") || value.contains("nak") || value.contains("error")) return "Failed";
        return "Success";
    }

    private boolean isSuccessStatus(String status) {
        return "Success".equals(status);
    }

    private boolean isFailedStatus(String status) {
        return "Failed".equals(status) || "Rejected".equals(status) || "Cancelled".equals(status);
    }

    private double parseAmount(String amount) {
        if (amount == null || amount.isBlank() || "-".equals(amount)) {
            return 0d;
        }
        try {
            return Double.parseDouble(amount.replace(",", "").replaceAll("[^0-9.\\-]", ""));
        } catch (Exception ignore) {
            return 0d;
        }
    }

    private String formatAmount(double amount) {
        return String.format(Locale.US, "%,.2f", amount);
    }

    private String formatCount(long count) {
        return String.format(Locale.US, "%,d", count);
    }

    private String messageDescription(String messageType) {
        String type = blankToDefault(messageType, "-");
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "MT103" -> "Customer Credit Transfer";
            case "MT202" -> "General Financial Institution Transfer";
            case "MT199" -> "Free Format Message";
            case "MT940" -> "Customer Statement Message";
            case "MT950" -> "Statement Message";
            case "PACS.008", "PACS.008.001", "PACS.008.001.08" -> "FI to FI Customer Credit Transfer";
            case "PACS.009", "PACS.009.001", "PACS.009.001.08" -> "Financial Institution Credit Transfer";
            case "PACS.002", "PACS.002.001", "PACS.002.001.10" -> "Payment Status Report";
            case "CAMT.053", "CAMT.053.001" -> "Bank to Customer Statement";
            case "CAMT.056", "CAMT.056.001" -> "Payment Cancellation Request";
            default -> type;
        };
    }

    private String safeSheetName(String raw) {
        String cleaned = blankToDefault(raw, "Report").replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (cleaned.isBlank()) {
            cleaned = "Report";
        }
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private Map<String, Object> previewPayload(ReportLayout layout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemTitle", layout.systemTitle);
        payload.put("reportCode", layout.reportCode);
        payload.put("reportTitle", layout.reportTitle);
        payload.put("runDate", layout.runDate);
        payload.put("totalMessageCount", layout.totalMessageCount);
        payload.put("criteriaRows", layout.criteriaRows);
        List<Map<String, Object>> sections = new ArrayList<>();
        for (LayoutBlock block : layout.blocks) {
            if (block instanceof TableBlock tableBlock) {
                Map<String, Object> section = new LinkedHashMap<>();
                section.put("title", tableBlock.title);
                section.put("headers", tableBlock.headers);
                section.put("rows", tableBlock.rows);
                sections.add(section);
            }
        }
        payload.put("sections", sections);
        return payload;
    }

    private String mapDirection(String raw) {
        if (raw == null) return "-";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "I" -> "Inbound";
            case "O" -> "Outbound";
            default -> raw;
        };
    }

    private String dateOnly(String raw) {
        if (raw == null) return "-";
        Instant instant = parseInstant(raw);
        return instant == null ? raw : DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC).format(instant);
    }

    private String sequenceRef(Document protocolParams, Document block1) {
        String session = firstNonBlank(protocolParams.getString("sessionNumber"), block1.getString("SessionNumber"));
        String sequence = firstNonBlank(protocolParams.getString("sequenceNumber"), block1.getString("SequenceNumber"));
        if (session == null && sequence == null) return "-";
        return blankToDefault(session, "-") + "/" + blankToDefault(sequence, "-");
    }

    private boolean containsNack(Document status) {
        String combined = (blankToDefault(status.getString("reason"), "") + " " + blankToDefault(status.getString("message"), "")).toLowerCase(Locale.ROOT);
        return combined.contains("nack");
    }

    private String extractUetr(Document doc, String rawPayload) {
        Document extracted = documentAt(doc, "extractedFields");
        String direct = firstNonBlank(extracted.getString("uetr"), doc.getString("uetr"));
        if (direct != null) return direct;
        if (rawPayload != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{121:([^}]+)}").matcher(rawPayload);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "-";
    }

    private Document documentAt(Document doc, String key) {
        if (doc == null) return new Document();
        Object value = doc.get(key);
        return value instanceof Document document ? document : new Document();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String prettyMonth(Object value) {
        String string = stringValue(value);
        if (string == null) return "-";
        Instant instant = parseInstant(string);
        return instant == null ? string : DateTimeFormatter.ofPattern("MMMM yyyy").withZone(ZoneOffset.UTC).format(instant);
    }

    private static class ReportData {
        List<DetailRow> detailRows = List.of();
        List<List<String>> reportSummaryRows = List.of();
        List<List<String>> summaryRows = List.of();
        List<List<String>> departmentRows = List.of();
        List<List<String>> mtBreakdownRows = List.of();
        List<List<String>> mxBreakdownRows = List.of();
        List<List<String>> currencySummaryRows = List.of();
        List<List<String>> statusSummaryRows = List.of();
        List<GapRow> gapRows = List.of();
        List<FinancialSummaryRow> financialSummaryRows = List.of();
        List<DetailRow> duplicateRows = List.of();
        String primaryBank = "-";
        String primaryLogicalTerminal = "-";
        String totalCurrencyLabel = "-";
        int totalIncoming;
        int totalOutgoingExcluding950;
        int total950;
        int totalMessages;
    }

    private static class DetailRow {
        String bank;
        String date;
        String direction;
        String messageType;
        String status;
        String count;
        String reference;
        String messageReference;
        String transactionReference;
        String receiver;
        String sender;
        String valueDate;
        String amount;
        String currency;
        String sequenceRef;
        String nack;
        String logicalTerminal;
        String correspondent;
        String verification;
        String userIssuedAsPdc;
        String creationDate;
        String department;
        String channel;
        String sessionNumber;
        String sequenceNumber;
        String rawPayload;
        String uetr;
        String duplicateGroupId = "-";
        int duplicateCount;
    }

    private static class GapRow {
        String leftRef;
        String rightRef;
        String missing;
    }

    private static class FinancialSummaryRow {
        String sender;
        String messageType;
        String currency;
        int count;
        String totalAmount;
    }

    private class Mrm002Group {
        final String department;
        int total;
        int success;
        int failed;
        int pending;
        int repaired;
        double debit;
        double credit;
        String currency = "N/A";

        Mrm002Group(String department) {
            this.department = department;
        }

        void accept(String status, double amount, boolean debitSide, String currencyValue) {
            total++;
            if (isSuccessStatus(status)) success++;
            if (isFailedStatus(status)) failed++;
            if ("Pending".equals(status)) pending++;
            if ("Repaired".equals(status)) repaired++;
            if (!"N/A".equals(currencyValue)) currency = currencyValue;
            if (debitSide) {
                debit += amount;
            } else {
                credit += amount;
            }
        }

        List<String> toRow() {
            return List.of(
                    department,
                    formatCount(total),
                    formatCount(success),
                    formatCount(failed),
                    formatCount(pending),
                    formatCount(repaired),
                    formatAmount(debit),
                    formatAmount(credit),
                    formatAmount(credit - debit),
                    currency
            );
        }
    }

    private class Mrm002TypeGroup {
        final String type;
        final String description;
        final String department;
        final String currency;
        int total;
        int success;
        int failed;
        double amount;

        Mrm002TypeGroup(String type, String description, String department, String currency) {
            this.type = type;
            this.description = description;
            this.department = department;
            this.currency = currency;
        }

        void accept(String status, double nextAmount) {
            total++;
            if (isSuccessStatus(status)) success++;
            if (isFailedStatus(status)) failed++;
            amount += nextAmount;
        }

        List<String> toRow() {
            return List.of(type, description, department, formatCount(total), formatCount(success), formatCount(failed), formatAmount(amount), currency);
        }
    }

    private class Mrm002CurrencyGroup {
        final String currency;
        int total;
        int debitCount;
        int creditCount;
        int success;
        int failed;
        double debitAmount;
        double creditAmount;

        Mrm002CurrencyGroup(String currency) {
            this.currency = currency;
        }

        void accept(String status, double amount, boolean debitSide) {
            total++;
            if (debitSide) {
                debitCount++;
                debitAmount += amount;
            } else {
                creditCount++;
                creditAmount += amount;
            }
            if (isSuccessStatus(status)) success++;
            if (isFailedStatus(status)) failed++;
        }

        List<String> toRow() {
            return List.of(currency, formatCount(total), formatCount(debitCount), formatCount(creditCount), formatAmount(debitAmount), formatAmount(creditAmount), formatAmount(creditAmount - debitAmount), formatCount(success), formatCount(failed));
        }
    }

    private class Mrm002StatusGroup {
        final String status;
        int total;
        double amount;
        final Set<String> departments = new LinkedHashSet<>();

        Mrm002StatusGroup(String status) {
            this.status = status;
        }

        void accept(double nextAmount, String department) {
            total++;
            amount += nextAmount;
            departments.add(blankToDefault(department, "None"));
        }

        List<String> toRow(long grandTotal) {
            double percentage = grandTotal == 0 ? 0d : (total * 100d / grandTotal);
            return List.of(status, formatCount(total), String.format(Locale.US, "%.2f%%", percentage), formatAmount(amount), formatCount(departments.size()));
        }
    }

    private static class ReportLayout {
        String reportCode;
        String systemTitle;
        String reportTitle;
        String runDate;
        List<List<String>> criteriaRows = List.of();
        List<LayoutBlock> blocks = new ArrayList<>();
        int totalMessageCount;
    }

    private abstract static class LayoutBlock {
    }

    private static class TextBlock extends LayoutBlock {
        final String text;

        private TextBlock(String text) {
            this.text = text;
        }
    }

    private static class TableBlock extends LayoutBlock {
        final String title;
        final List<String> headers;
        final List<List<String>> rows;
        final List<List<String>> footerRows;

        private TableBlock(String title, List<String> headers, List<List<String>> rows, List<List<String>> footerRows) {
            this.title = title;
            this.headers = headers;
            this.rows = rows;
            this.footerRows = footerRows;
        }
    }
}
