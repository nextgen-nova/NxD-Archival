package com.swift.platform.service;

import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Component
public class ReportQueryRegistry {

    private final Map<String, Consumer<QueryParts>> reportQueries = new LinkedHashMap<>();

    public ReportQueryRegistry() {
        /*
         * Future report Mongo filters should be added here.
         * Frontend sends criteria.reportCode, this registry picks the matching
         * report-code query and then applies the common frontend filters.
         */
        reportQueries.put("SWD003", parts -> direction(parts, "I"));
        reportQueries.put("SWD004", parts -> direction(parts, "O"));
        reportQueries.put("SWS950", parts -> parts.and(new Document("header.messageTypeCode", new Document("$in", List.of("MT950", "MT940")))));
        reportQueries.put("MRM002", this::monthlyTransactionAllDepartments);
        reportQueries.put("SWM002", this::monthlyTransactionAllDepartments);
        reportQueries.put("ISN001", parts -> direction(parts, "I"));
        reportQueries.put("OSN001", parts -> direction(parts, "O"));
        reportQueries.put("MSGNACK", parts -> parts.or(List.of(
                regex("status.reason", "nack"),
                regex("status.message", "nack"),
                regex("status.current", "nack"),
                regex("body.rawPayload", "nak|nack|rejected")
        )));
    }

    public Document buildMessageMatch(Map<String, Object> criteria) {
        QueryParts parts = new QueryParts();
        appendDateRange(parts, criteria);

        String reportCode = stringValue(criteria.get("reportCode"));
        reportQueries.getOrDefault(blankToDefault(reportCode, ""), ignored -> { }).accept(parts);

        appendRegexOr(parts, List.of("header.senderAddress", "protocolParams.requestor", "protocolParams.requester"), stringValue(criteria.get("sender")));
        appendRegexOr(parts, List.of("header.receiverAddress", "protocolParams.responder"), stringValue(criteria.get("receiver")));
        appendRegexOr(parts, List.of("header.owner", "header.senderAddress", "header.receiverAddress", "protocolParams.logicalTerminal"), stringValue(criteria.get("bankName")));
        appendRegexOr(parts, List.of("header.messageTypeCode", "messageType"), stringValue(criteria.get("messageType")));
        appendRegexOr(parts, List.of("header.owner", "owner", "department", "ownerUnit"), stringValue(criteria.get("department")));
        appendRegexOr(parts, List.of("extractedFields.currency", "currency"), stringValue(criteria.get("currency")));
        appendRegexOr(parts, List.of("status.current", "status.reason", "status.phase", "status.action"), stringValue(criteria.get("status")));
        appendRegexOr(parts, List.of("header.backendChannel", "header.networkChannel", "backendChannel", "networkChannel"), stringValue(criteria.get("branch")));
        appendDirectionFilter(parts, stringValue(criteria.get("direction")));
        appendFormatFilter(parts, stringValue(criteria.get("messageFormat")));

        return parts.toDocument();
    }

    private void monthlyTransactionAllDepartments(QueryParts parts) {
        parts.and(new Document("messageFamily", new Document("$in", List.of("MT", "MX"))));
        parts.or(List.of(
                new Document("header.messageTypeCode", new Document("$exists", true)),
                new Document("messageType", new Document("$exists", true)),
                new Document("messageTypeCode", new Document("$exists", true))
        ));
    }

    private void appendDateRange(QueryParts parts, Map<String, Object> criteria) {
        Instant start = parseInstant(stringValue(criteria.get("startDate")));
        Instant end = parseInstant(stringValue(criteria.get("endDate")));
        if (start == null && end == null) {
            return;
        }
        Document range = new Document();
        if (start != null) range.append("$gte", start.toString());
        if (end != null) range.append("$lte", end.toString());
        parts.and(new Document("header.dateCreated", range));
    }

    private void appendRegexOr(QueryParts parts, List<String> fields, String value) {
        if (isEmptyFilter(value) || fields == null || fields.isEmpty()) {
            return;
        }
        List<Document> conditions = new ArrayList<>();
        for (String field : fields) {
            conditions.add(regex(field, value));
        }
        parts.or(conditions);
    }

    private void appendDirectionFilter(QueryParts parts, String direction) {
        if (direction == null || direction.isBlank() || direction.equalsIgnoreCase("All")) {
            return;
        }
        String normalized = direction.toLowerCase(Locale.ROOT);
        String finDirection = normalized.startsWith("in") ? "I" : normalized.startsWith("out") ? "O" : null;
        if (finDirection == null) {
            return;
        }
        parts.or(List.of(
                new Document("protocolParams.FIN.ApplicationHeaderBlock.DirectionID", finDirection),
                regex("header.direction", direction),
                regex("direction", direction)
        ));
    }

    private void appendFormatFilter(QueryParts parts, String messageFormat) {
        if (isEmptyFilter(messageFormat) || messageFormat.toUpperCase(Locale.ROOT).contains("MT & MX")) {
            return;
        }
        String normalized = messageFormat.toUpperCase(Locale.ROOT);
        if (normalized.contains("MT")) {
            parts.and(new Document("messageFamily", "MT"));
        } else if (normalized.contains("MX")) {
            parts.and(new Document("messageFamily", "MX"));
        }
    }

    private static void direction(QueryParts parts, String finDirection) {
        parts.and(new Document("protocolParams.FIN.ApplicationHeaderBlock.DirectionID", finDirection));
    }

    private static Document regex(String field, String value) {
        return new Document(field, new Document("$regex", value).append("$options", "i"));
    }

    private boolean isEmptyFilter(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "any".equals(normalized) || normalized.startsWith("all");
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

    private String stringValue(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class QueryParts {
        private final List<Document> andConditions = new ArrayList<>();

        private void and(Document condition) {
            if (condition != null && !condition.isEmpty()) {
                andConditions.add(condition);
            }
        }

        private void or(List<Document> conditions) {
            if (conditions != null && !conditions.isEmpty()) {
                and(new Document("$or", conditions));
            }
        }

        private Document toDocument() {
            if (andConditions.isEmpty()) {
                return new Document();
            }
            if (andConditions.size() == 1) {
                return andConditions.get(0);
            }
            return new Document("$and", andConditions);
        }
    }
}
