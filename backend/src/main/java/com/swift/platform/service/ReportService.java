package com.swift.platform.service;

import com.swift.platform.dto.ReportGenerateRequest;
import com.swift.platform.dto.ReportHistoryPageResponse;
import com.swift.platform.dto.ReportHistoryResponse;
import com.swift.platform.dto.ReportTemplateRequest;
import com.swift.platform.dto.ReportTemplateResponse;
import com.swift.platform.config.AppConfig;
import com.swift.platform.model.ReportHistory;
import com.swift.platform.model.ReportStatus;
import com.swift.platform.model.ReportTemplate;
import com.swift.platform.repository.ReportHistoryRepository;
import com.swift.platform.repository.ReportTemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.bson.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ReportHistoryRepository reportHistoryRepository;
    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;

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

        if (!reportHistoryRepository.existsByCreatedBy(DEFAULT_USER)) {
            reportHistoryRepository.saveAll(List.of(
                    buildSeedHistory(DEFAULT_USER, "Online_Generate_SWD003_A0016699_20251015_175219.pdf", "PDF", ReportStatus.GENERATED,
                            defaultCriteria("custom", "SWD003", Map.of("bankName", "HBACED", "messageFormat", "All - MT & MX")),
                            Instant.now().minus(Duration.ofDays(2)), null),
                    buildSeedHistory(DEFAULT_USER, "Online_Generate_MRM002_A0016699_20251015_184245.xlsx", "Excel", ReportStatus.GENERATED,
                            defaultCriteria("custom", "MRM002", Map.of("department", "ACT", "messageFormat", "All - MT & MX")),
                            Instant.now().minus(Duration.ofDays(3)), null),
                    buildSeedHistory(DEFAULT_USER, "Online_Generate_UserTrafficReport_A0016699_20251014_132548.csv", "CSV", ReportStatus.IN_PROGRESS,
                            defaultCriteria("traffic", "UTR001", Map.of("receiver", "HBACED", "sender", "A0016699", "messageType", "User Traffic", "messageFormat", "All - MT & MX")),
                            Instant.now().minus(Duration.ofMinutes(25)), null),
                    buildSeedHistory(DEFAULT_USER, "Online_Generate_PossibleDuplicateMsg_A0016699_20251013_194010.pdf", "PDF", ReportStatus.FAILED,
                            defaultCriteria("traffic", "DUPMSG", Map.of("application", "ALL - MT & MX", "software", "Any")),
                            Instant.now().minus(Duration.ofDays(4)), "Backend processing failed.")
            ));
        }
    }

    public List<ReportTemplateResponse> listTemplates(String employeeId) {
        return reportTemplateRepository.findByCreatedByOrderByCreationDateDesc(normalizeEmployee(employeeId))
                .stream()
                .map(this::toTemplateResponse)
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

    public ReportHistoryResponse runTemplate(String employeeId, String templateId) {
        ReportTemplate template = reportTemplateRepository.findByIdAndCreatedBy(templateId, normalizeEmployee(employeeId))
                .orElseThrow(() -> new IllegalArgumentException("Template not found."));
        ReportHistory history = ReportHistory.builder()
                .createdBy(template.getCreatedBy())
                .fileName(buildFileName(template.getCreatedBy(), stringValue(template.getCriteria().get("reportCode")), template.getFormat()))
                .status(ReportStatus.IN_PROGRESS)
                .generationTime(Instant.now())
                .format(template.getFormat())
                .criteria(new LinkedHashMap<>(template.getCriteria()))
                .build();
        return toHistoryResponse(reportHistoryRepository.save(history));
    }

    public ReportHistoryResponse generate(String employeeId, ReportGenerateRequest request) {
        Map<String, Object> criteria = sanitizeCriteria(request.getCriteria());
        String format = normalizeFormat(request.getFormat());
        validateRange(criteria);
        ReportHistory history = reportHistoryRepository.save(ReportHistory.builder()
                .createdBy(normalizeEmployee(employeeId))
                .fileName(buildFileName(normalizeEmployee(employeeId), stringValue(criteria.get("reportCode")), format))
                .status(ReportStatus.GENERATED)
                .generationTime(Instant.now())
                .format(format)
                .criteria(criteria)
                .contentType(contentType(format))
                .contentBase64(Base64.getEncoder().encodeToString(buildArtifactBytes(criteria, format)))
                .build());
        return toHistoryResponse(history);
    }

    public ReportHistoryPageResponse listHistory(String employeeId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String user = normalizeEmployee(employeeId);
        Page<ReportHistory> result = reportHistoryRepository.findByCreatedByOrderByGenerationTimeDesc(user, PageRequest.of(safePage, safeSize));
        long generatedCount = reportHistoryRepository.findByCreatedByAndStatus(user, ReportStatus.GENERATED).size();
        long inProgressCount = reportHistoryRepository.findByCreatedByAndStatus(user, ReportStatus.IN_PROGRESS).size();
        long failedCount = reportHistoryRepository.findByCreatedByAndStatus(user, ReportStatus.FAILED).size();
        return ReportHistoryPageResponse.builder()
                .content(result.getContent().stream().map(this::toHistoryResponse).toList())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .pageNumber(result.getNumber())
                .pageSize(result.getSize())
                .first(result.isFirst())
                .last(result.isLast())
                .generatedCount(generatedCount)
                .inProgressCount(inProgressCount)
                .failedCount(failedCount)
                .build();
    }

    public ReportHistoryPageResponse refreshHistory(String employeeId, int page, int size) {
        String user = normalizeEmployee(employeeId);
        List<ReportHistory> inProgress = reportHistoryRepository.findByCreatedByAndStatus(user, ReportStatus.IN_PROGRESS);
        for (int index = 0; index < inProgress.size(); index++) {
            ReportHistory item = inProgress.get(index);
            item.setStatus(index % 3 == 2 ? ReportStatus.FAILED : ReportStatus.GENERATED);
            item.setErrorMessage(item.getStatus() == ReportStatus.FAILED ? "Generation failed during refresh." : null);
            if (item.getStatus() == ReportStatus.GENERATED) {
                item.setContentType(contentType(item.getFormat()));
                item.setContentBase64(Base64.getEncoder().encodeToString(buildArtifactBytes(item.getCriteria(), item.getFormat())));
            }
        }
        if (!inProgress.isEmpty()) {
            reportHistoryRepository.saveAll(inProgress);
        }
        return listHistory(user, page, size);
    }

    public void deleteHistory(String employeeId, String historyId) {
        ReportHistory history = reportHistoryRepository.findByIdAndCreatedBy(historyId, normalizeEmployee(employeeId))
                .orElseThrow(() -> new IllegalArgumentException("History record not found."));
        reportHistoryRepository.delete(history);
    }

    public ReportDownload download(String employeeId, String historyId) {
        ReportHistory history = reportHistoryRepository.findByIdAndCreatedBy(historyId, normalizeEmployee(employeeId))
                .orElseThrow(() -> new IllegalArgumentException("History record not found."));
        if (history.getStatus() != ReportStatus.GENERATED || history.getContentBase64() == null) {
            throw new IllegalStateException("Report is not ready for download.");
        }
        byte[] bytes = Base64.getDecoder().decode(history.getContentBase64());
        return new ReportDownload(new ByteArrayResource(bytes), history.getFileName(), history.getContentType());
    }

    public record ReportDownload(Resource resource, String fileName, String contentType) {}

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

    private ReportHistoryResponse toHistoryResponse(ReportHistory history) {
        return ReportHistoryResponse.builder()
                .id(history.getId())
                .fileName(history.getFileName())
                .status(displayStatus(history.getStatus()))
                .generationTime(history.getGenerationTime())
                .format(history.getFormat())
                .criteria(history.getCriteria())
                .downloadReady(history.getStatus() == ReportStatus.GENERATED && history.getContentBase64() != null)
                .downloadUrl(history.getStatus() == ReportStatus.GENERATED && history.getContentBase64() != null ? "/api/reports/history/" + history.getId() + "/download" : null)
                .errorMessage(history.getErrorMessage())
                .build();
    }

    private ReportHistory buildSeedHistory(String createdBy,
                                           String fileName,
                                           String format,
                                           ReportStatus status,
                                           Map<String, Object> criteria,
                                           Instant generationTime,
                                           String errorMessage) {
        return ReportHistory.builder()
                .createdBy(createdBy)
                .fileName(fileName)
                .status(status)
                .generationTime(generationTime)
                .format(format)
                .criteria(criteria)
                .contentType(status == ReportStatus.GENERATED ? contentType(format) : null)
                .contentBase64(status == ReportStatus.GENERATED
                        ? Base64.getEncoder().encodeToString(buildArtifactBytes(criteria, format))
                        : null)
                .errorMessage(errorMessage)
                .build();
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
        return switch (format) {
            case "CSV" -> buildCsvBytes(criteria, buildReportData(criteria));
            case "Excel" -> buildExcelBytes(criteria, buildReportData(criteria));
            default -> buildPdfBytes(criteria, format, buildReportData(criteria));
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

    private byte[] buildCsvBytes(Map<String, Object> criteria, ReportData data) {
        List<String> rows = new ArrayList<>();
        String reportCode = stringValue(criteria.get("reportCode"));
        rows.add("\"SCBD SWIFT SYSTEM\"");
        rows.add("\"" + escapeCsv(reportHeader(reportCode)) + "\"");
        rows.add("\"Run Date\",\"" + escapeCsv(prettyInstant(Instant.now())) + "\"");
        rows.add("\"Start Date\",\"" + escapeCsv(prettyDateTimeValue(criteria.get("startDate"))) + "\",\"End Date\",\"" + escapeCsv(prettyDateTimeValue(criteria.get("endDate"))) + "\"");
        rows.add("");
        rows.addAll(csvRowsForReport(reportCode, criteria, data));
        rows.add("");
        rows.add("\"END OF REPORT\"");
        return String.join("\n", rows).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildExcelBytes(Map<String, Object> criteria, ReportData data) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Report");
            String reportCode = stringValue(criteria.get("reportCode"));
            List<List<String>> rows = excelRowsForReport(reportCode, criteria, data);
            int rowIndex = 0;
            for (List<String> dataRow : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int cellIndex = 0; cellIndex < dataRow.size(); cellIndex++) {
                    Cell cell = row.createCell(cellIndex);
                    cell.setCellValue(dataRow.get(cellIndex));
                }
            }
            int maxCols = rows.stream().mapToInt(List::size).max().orElse(1);
            for (int column = 0; column < maxCols; column++) {
                sheet.autoSizeColumn(column);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build Excel report.", exception);
        }
    }

    private byte[] buildPdfBytes(Map<String, Object> criteria, String format, ReportData data) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float margin = 48f;
            float y = page.getMediaBox().getHeight() - margin;
            PDPageContentStream content = new PDPageContentStream(document, page);
            try {
                String reportCode = stringValue(criteria.get("reportCode"));
                List<String> lines = pdfLinesForReport(reportCode, criteria, format, data);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (y < margin + 20) {
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - margin;
                    }
                    PDType1Font font = index <= 1 ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
                    float size = index == 0 ? 16f : (index == 1 ? 13f : 10f);
                    float gap = index <= 1 ? 22f : 15f;
                    y = writePdfLine(content, margin, y, font, size, sanitizePdfText(line), gap);
                }
            } finally {
                content.close();
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build PDF report.", exception);
        }
    }

    private float writePdfLine(PDPageContentStream content,
                               float x,
                               float y,
                               PDType1Font font,
                               float size,
                               String text,
                               float lineGap) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
        return y - lineGap;
    }

    private String sanitizePdfText(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ");
    }

    private String displayStatus(ReportStatus status) {
        if (status == null) return "Failed";
        return switch (status) {
            case GENERATED -> "Generated";
            case IN_PROGRESS -> "In Progress";
            case FAILED -> "Failed";
        };
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
                lines.add("I/O | Correspon | TRN Refer | Creation | Verification | MT/MX | Ccy | Amount | Value Date PDC");
                if (data.detailRows.isEmpty()) {
                    lines.add("No records found.");
                } else {
                    data.detailRows.stream().limit(20).forEach(row ->
                            lines.add(String.join(" | ", row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate))
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
                rows.add(List.of("I/O", "Correspon", "TRN Refer", "Creation O", "Verification", "MT/MX", "Ccy", "Amount", "Value Date PDC", "User Issued as PDC"));
                if (data.detailRows.isEmpty()) {
                    rows.add(List.of("No records found."));
                } else {
                    data.detailRows.forEach(row -> rows.add(List.of(row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate, row.userIssuedAsPdc)));
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
                rows.add("\"I/O\",\"Correspon\",\"TRN Refer\",\"Creation\",\"Verification\",\"MT/MX\",\"Ccy\",\"Amount\",\"Value Date PDC\"");
                if (data.detailRows.isEmpty()) {
                    rows.add("\"No records found\"");
                } else {
                    data.detailRows.forEach(row -> rows.add(csvLine(row.direction, row.correspondent, row.reference, row.creationDate, row.verification, row.messageType, row.currency, row.amount, row.valueDate)));
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
            case "MRM001", "MRM002", "SWM002" -> populateDepartmentSummary(data);
            case "ISN001", "OSN001" -> data.gapRows = buildGapRows(data.detailRows);
            case "FINMSG" -> data.financialSummaryRows = buildFinancialSummary(data.detailRows);
            default -> {
                data.summaryRows = List.of();
                data.departmentRows = List.of();
                data.gapRows = List.of();
                data.financialSummaryRows = List.of();
            }
        }
        return data;
    }

    private List<Document> queryMessages(Map<String, Object> criteria) {
        Document match = new Document();
        Instant start = parseInstant(stringValue(criteria.get("startDate")));
        Instant end = parseInstant(stringValue(criteria.get("endDate")));
        if (start != null || end != null) {
            Document range = new Document();
            if (start != null) range.append("$gte", start.toString());
            if (end != null) range.append("$lte", end.toString());
            match.append("header.dateCreated", range);
        }

        String reportCode = blankToDefault(stringValue(criteria.get("reportCode")), "");
        if ("SWD003".equals(reportCode)) {
            match.append("protocolParams.FIN.ApplicationHeaderBlock.DirectionID", "I");
        } else if ("SWD004".equals(reportCode)) {
            match.append("protocolParams.FIN.ApplicationHeaderBlock.DirectionID", "O");
        } else if ("SWS950".equals(reportCode)) {
            match.append("header.messageTypeCode", new Document("$in", List.of("MT950", "MT940")));
        } else if ("MSGNACK".equals(reportCode)) {
            match.append("$or", List.of(
                    new Document("status.reason", new Document("$regex", "nack").append("$options", "i")),
                    new Document("status.message", new Document("$regex", "nack").append("$options", "i")),
                    new Document("status.current", new Document("$regex", "nack").append("$options", "i"))
            ));
        }

        appendRegexOr(match, List.of("header.senderAddress", "protocolParams.requestor", "protocolParams.requester"), stringValue(criteria.get("sender")));
        appendRegexOr(match, List.of("header.receiverAddress", "protocolParams.responder"), stringValue(criteria.get("receiver")));
        appendRegexOr(match, List.of("header.owner", "header.senderAddress", "header.receiverAddress", "protocolParams.logicalTerminal"), stringValue(criteria.get("bankName")));
        appendRegexOr(match, List.of("header.messageTypeCode", "messageType"), stringValue(criteria.get("messageType")));
        appendFormatFilter(match, stringValue(criteria.get("messageFormat")));

        return mongoTemplate.getCollection(appConfig.getSwiftCollection())
                .find(match)
                .limit(5000)
                .into(new ArrayList<>());
    }

    private void appendRegex(Document match, String field, String value) {
        if (value != null && !value.isBlank()) {
            match.append(field, new Document("$regex", value).append("$options", "i"));
        }
    }

    private void appendRegexOr(Document match, List<String> fields, String value) {
        if (value == null || value.isBlank() || fields == null || fields.isEmpty()) {
            return;
        }
        List<Document> conditions = new ArrayList<>();
        for (String field : fields) {
            conditions.add(new Document(field, new Document("$regex", value).append("$options", "i")));
        }
        appendOrCondition(match, conditions);
    }

    @SuppressWarnings("unchecked")
    private void appendOrCondition(Document match, List<Document> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        Object current = match.get("$or");
        if (current instanceof List<?> existing) {
            List<Document> merged = new ArrayList<>();
            for (Object item : existing) {
                if (item instanceof Document doc) {
                    merged.add(doc);
                }
            }
            merged.addAll(conditions);
            match.put("$or", merged);
        } else {
            match.put("$or", conditions);
        }
    }

    private void appendFormatFilter(Document match, String messageFormat) {
        if (messageFormat == null || messageFormat.isBlank() || messageFormat.startsWith("All")) {
            return;
        }
        if (messageFormat.toUpperCase(Locale.ROOT).contains("MT")) {
            match.append("messageFamily", "MT");
        } else if (messageFormat.toUpperCase(Locale.ROOT).contains("MX")) {
            match.append("messageFamily", "MX");
        }
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

    private List<GapRow> buildGapRows(List<DetailRow> rows) {
        List<DetailRow> filtered = rows.stream()
                .filter(row -> row.sequenceRef != null && row.sequenceRef.contains("/"))
                .sorted(Comparator.comparing(row -> blankToDefault(row.logicalTerminal, "") + "|" + blankToDefault(row.sequenceRef, "")))
                .toList();
        List<GapRow> gaps = new ArrayList<>();
        for (int i = 1; i < filtered.size(); i++) {
            DetailRow previous = filtered.get(i - 1);
            DetailRow current = filtered.get(i);
            if (!Objects.equals(previous.logicalTerminal, current.logicalTerminal)) continue;
            Integer prev = parseSequence(previous.sequenceRef);
            Integer next = parseSequence(current.sequenceRef);
            if (prev == null || next == null || next - prev <= 1) continue;
            GapRow gap = new GapRow();
            gap.leftRef = previous.sequenceRef;
            gap.rightRef = current.sequenceRef;
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
        List<List<String>> summaryRows = List.of();
        List<List<String>> departmentRows = List.of();
        List<GapRow> gapRows = List.of();
        List<FinancialSummaryRow> financialSummaryRows = List.of();
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
}
