package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.PagedResponse;
import com.swift.platform.dto.RawCopyDTO;
import com.swift.platform.dto.RawCopiesResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for the amp_raw_copies collection.
 *
 * Schema:
 *   _id, messageReference, messageId, rawInput (XML),
 *   inputType, source, isDuplicate, currentStatus,
 *   senderAddress, receiverAddress, messageTypeCode,
 *   protocol, direction, ampDateReceived, receivedAt, _class
 *
 * Key feature: multiple raw copies can share the same messageReference.
 * The search returns a flat paged list; the frontend groups by messageReference.
 */
@Service
@RequiredArgsConstructor
public class RawCopyService {

    private static final Pattern MX_FROM_BIC_PATTERN = Pattern.compile("(?is)<(?:\\w+:)?Fr>.*?<(?:\\w+:)?BICFI>([^<]+)</(?:\\w+:)?BICFI>");
    private static final Pattern MX_TO_BIC_PATTERN = Pattern.compile("(?is)<(?:\\w+:)?To>.*?<(?:\\w+:)?BICFI>([^<]+)</(?:\\w+:)?BICFI>");

    private final MongoTemplate mongoTemplate;
    private final AppConfig     appConfig;

    // ── Search raw copies ──────────────────────────────────────────────────
    public PagedResponse<RawCopyDTO> search(
            String messageReference,
            String messageId,
            String sender,
            String receiver,
            String messageTypeCode,
            String direction,
            String currentStatus,
            String protocol,
            String inputType,
            String source,
            Boolean isDuplicate,
            String startDate,
            String endDate,
            String freeText,
            int page, int size) {

        String col = appConfig.getRawCopiesCollection();
        size = Math.min(size, appConfig.getMaxPageSize());

        List<Criteria> criteria = new ArrayList<>();

        // Reference / ID filters
        if (notBlank(messageReference))
            criteria.add(Criteria.where("messageReference").regex(escapeRegex(messageReference), "i"));
        if (notBlank(messageId))
            criteria.add(Criteria.where("messageId").regex(escapeRegex(messageId), "i"));

        // Parties
        if (notBlank(sender))
            criteria.add(buildBicCriteria(sender, true));
        if (notBlank(receiver))
            criteria.add(buildBicCriteria(receiver, false));

        // Classification
        if (notBlank(messageTypeCode))
            criteria.add(Criteria.where("messageTypeCode").is(messageTypeCode));
        if (notBlank(direction))
            criteria.add(Criteria.where("direction").is(direction));
        if (notBlank(currentStatus))
            criteria.add(Criteria.where("currentStatus").is(currentStatus));
        if (notBlank(protocol))
            criteria.add(Criteria.where("protocol").is(protocol));
        if (notBlank(inputType))
            criteria.add(Criteria.where("inputType").is(inputType));
        if (notBlank(source))
            criteria.add(Criteria.where("source").is(source));

        // Boolean flag
        if (isDuplicate != null)
            criteria.add(Criteria.where("isDuplicate").is(isDuplicate));

        // Bug #2 fix: receivedAt is stored as ISODate (Date object) in MongoDB.
        // Comparing a plain String to a Date causes a type mismatch → 0 results.
        // Solution: parse the date strings to java.util.Date before building the criteria.
        if (notBlank(startDate) || notBlank(endDate)) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                if (notBlank(startDate) && notBlank(endDate)) {
                    java.util.Date start = sdf.parse(startDate);
                    // Add 1 day - 1ms to endDate so the full end day is included
                    java.util.Date end   = new java.util.Date(sdf.parse(endDate).getTime() + 86399999L);
                    criteria.add(Criteria.where("receivedAt").gte(start).lte(end));
                } else if (notBlank(startDate)) {
                    java.util.Date start = sdf.parse(startDate);
                    criteria.add(Criteria.where("receivedAt").gte(start));
                } else {
                    java.util.Date end = new java.util.Date(sdf.parse(endDate).getTime() + 86399999L);
                    criteria.add(Criteria.where("receivedAt").lte(end));
                }
            } catch (java.text.ParseException ignored) {
                // invalid date string — skip filter rather than crash
            }
        }

        // Free text across key fields
        if (notBlank(freeText)) {
            String rx = escapeRegex(freeText);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("messageReference").regex(rx, "i"),
                    Criteria.where("messageId").regex(rx, "i"),
                    Criteria.where("senderAddress").regex(rx, "i"),
                    Criteria.where("receiverAddress").regex(rx, "i"),
                    Criteria.where("rawInput").regex(rx, "i"),
                    Criteria.where("currentStatus").regex(rx, "i"),
                    Criteria.where("rawInput").regex(rx, "i")
            ));
        }

        Query query = criteria.isEmpty()
                ? new Query()
                : new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));

        long total = mongoTemplate.count(query, Document.class, col);

        query.skip((long) page * size)
                .limit(size)
                .with(Sort.by(Sort.Direction.DESC, "receivedAt").and(Sort.by(Sort.Direction.DESC, "_id")));

        List<Document> docs = mongoTemplate.find(query, Document.class, col);
        List<RawCopyDTO> rows = docs.stream().map(this::toDTO).collect(Collectors.toList());

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        PagedResponse<RawCopyDTO> response = new PagedResponse<RawCopyDTO>();
        response.setContent(rows);
        response.setTotalElements(total);
        response.setTotalPages(totalPages);
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setFirst(page == 0);
        response.setLast(page >= totalPages - 1);
        response.setTotalExact(true);
        response.setHasNext(page < totalPages - 1);
        response.setNextCursor(null);
        return response;
    }

    // ── Get all raw copies for a specific messageReference ─────────────────
    public RawCopiesResponse getByMessageReference(String messageReference) {
        String col = appConfig.getRawCopiesCollection();
        Query q = new Query(Criteria.where("messageReference").is(messageReference))
                .with(Sort.by(Sort.Direction.ASC, "receivedAt"));
        List<Document> docs = mongoTemplate.find(q, Document.class, col);
        List<RawCopyDTO> dtos = docs.stream().map(this::toDTO).collect(Collectors.toList());
        return new RawCopiesResponse(messageReference, dtos.size(), dtos);
    }

    public Map<String, List<RawCopyDTO>> getByMessageReferences(List<String> messageReferences) {
        List<String> refs = messageReferences == null ? Collections.emptyList() : messageReferences.stream()
                .filter(this::notBlank)
                .distinct()
                .toList();

        if (refs.isEmpty()) return Collections.emptyMap();

        String col = appConfig.getRawCopiesCollection();
        Query q = new Query(Criteria.where("messageReference").in(refs))
                .with(Sort.by(Sort.Direction.ASC, "messageReference").and(Sort.by(Sort.Direction.ASC, "receivedAt")));

        List<Document> docs = mongoTemplate.find(q, Document.class, col);
        Map<String, List<RawCopyDTO>> grouped = new LinkedHashMap<>();
        refs.forEach(ref -> grouped.put(ref, new ArrayList<>()));
        docs.stream()
                .map(this::toDTO)
                .forEach(dto -> {
                    String ref = dto.getMessageReference();
                    if (notBlank(ref)) grouped.computeIfAbsent(ref, key -> new ArrayList<>()).add(dto);
                });
        return grouped;
    }

    // ── Dropdown distinct values ───────────────────────────────────────────
    public Map<String, List<String>> getDropdownOptions() {
        String col = appConfig.getRawCopiesCollection();
        Map<String, List<String>> opts = new LinkedHashMap<>();
        opts.put("messageTypeCodes", distinct("messageTypeCode", col));
        opts.put("directions",       distinct("direction",       col));
        opts.put("statuses",         distinct("currentStatus",   col));
        opts.put("protocols",        distinct("protocol",        col));
        opts.put("inputTypes",       distinct("inputType",       col));
        opts.put("sources",          distinct("source",          col));
        return opts;
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private RawCopyDTO toDTO(Document doc) {
        RawCopyDTO d = new RawCopyDTO();
        try {
            Object oid = doc.get("_id");
            d.setId(oid != null ? oid.toString() : null);
            d.setMessageReference(str(doc, "messageReference"));
            d.setMessageId(str(doc, "messageId"));
            d.setRawInput(str(doc, "rawInput"));
            d.setInputType(str(doc, "inputType"));
            d.setSource(str(doc, "source"));
            // isDuplicate may be Boolean, String "true"/"false", or absent
            Object dup = doc.get("isDuplicate");
            if (dup instanceof Boolean b)        d.setIsDuplicate(b);
            else if (dup instanceof String s)    d.setIsDuplicate(Boolean.parseBoolean(s));
            else if (dup != null)                d.setIsDuplicate(Boolean.parseBoolean(dup.toString()));
            d.setCurrentStatus(str(doc, "currentStatus"));
            String rawInput = d.getRawInput();
            d.setSenderAddress(firstNonBlank(resolveSenderBic(rawInput), str(doc, "senderAddress")));
            d.setReceiverAddress(firstNonBlank(resolveReceiverBic(rawInput), str(doc, "receiverAddress")));
            d.setMessageTypeCode(str(doc, "messageTypeCode"));
            d.setProtocol(str(doc, "protocol"));
            d.setDirection(str(doc, "direction"));
            // dates may be stored as Date objects or Strings
            d.setAmpDateReceived(dateStr(doc, "ampDateReceived"));
            d.setReceivedAt(dateStr(doc, "receivedAt"));
        } catch (Exception e) {
            // Return partial DTO rather than crashing the whole response
        }
        return d;
    }

    /** Safely get a String field — handles String, ObjectId, and other types */
    private String str(Document doc, String key) {
        Object v = doc.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return v.toString();
    }


    private String dateStr(Document doc, String key) {
        Object v = doc.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        if (v instanceof java.util.Date dt) return dt.toInstant().toString();
        return v.toString();
    }

    private List<String> distinct(String field, String col) {
        try {
            return mongoTemplate.findDistinct(new Query(), field, col, Object.class)
                    .stream()
                    .filter(v -> v != null)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Criteria buildBicCriteria(String value, boolean sender) {
        String rx = escapeRegex(value);
        return new Criteria().orOperator(
                Criteria.where(sender ? "senderAddress" : "receiverAddress").regex(rx, "i"),
                Criteria.where("rawInput").regex(sender ? rawInputSenderPattern(value) : rawInputReceiverPattern(value), "i")
        );
    }

    private String rawInputSenderPattern(String value) {
        String rx = escapeRegex(value);
        return "(?is)(<(?:\\w+:)?Fr>.*?<(?:\\w+:)?BICFI>" + rx + "</(?:\\w+:)?BICFI>|\\{1:[^}]*" + rx + ")";
    }

    private String rawInputReceiverPattern(String value) {
        String rx = escapeRegex(value);
        return "(?is)(<(?:\\w+:)?To>.*?<(?:\\w+:)?BICFI>" + rx + "</(?:\\w+:)?BICFI>|\\{2:[^}]*" + rx + ")";
    }

    private String resolveSenderBic(String rawInput) {
        return firstNonBlank(matchGroup(MX_FROM_BIC_PATTERN, rawInput), parseSenderBicFromFin(rawInput));
    }

    private String resolveReceiverBic(String rawInput) {
        return firstNonBlank(matchGroup(MX_TO_BIC_PATTERN, rawInput), parseReceiverBicFromFin(rawInput));
    }

    private String parseSenderBicFromFin(String rawInput) {
        String block1 = extractBlock(rawInput, "1");
        if (!notBlank(block1) || block1.length() < 15) {
            return null;
        }
        return block1.substring(3, 15);
    }

    private String parseReceiverBicFromFin(String rawInput) {
        String block2 = extractBlock(rawInput, "2");
        if (!notBlank(block2) || block2.length() < 16) {
            return null;
        }
        char direction = block2.charAt(0);
        if (direction == 'O') {
            int start = 16;
            int end = Math.min(start + 12, block2.length());
            return end > start ? block2.substring(start, end) : null;
        }
        if (direction == 'I') {
            int start = 4;
            int end = Math.min(start + 12, block2.length());
            return end > start ? block2.substring(start, end) : null;
        }
        return null;
    }

    private String extractBlock(String rawInput, String blockNo) {
        if (!notBlank(rawInput)) {
            return null;
        }
        String token = "{" + blockNo + ":";
        int start = rawInput.indexOf(token);
        if (start < 0) {
            return null;
        }
        int contentStart = start + token.length();
        int end = rawInput.indexOf('}', contentStart);
        return (end < 0 ? rawInput.substring(contentStart) : rawInput.substring(contentStart, end)).strip();
    }

    private String matchGroup(Pattern pattern, String rawInput) {
        if (!notBlank(rawInput)) {
            return null;
        }
        Matcher matcher = pattern.matcher(rawInput);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return notBlank(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean notBlank(String v) { return v != null && !v.isBlank(); }
    private String escapeRegex(String s) { return s.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0"); }
}
