package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.FailureDTO;
import com.swift.platform.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FailureService {

    private static final Pattern MESSAGE_REFERENCE_PATTERN = Pattern.compile("<MessageReference>(.*?)</MessageReference>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;

    public PagedResponse<FailureDTO> search(
            String messageReference,
            String errorCode,
            String stage,
            String inputType,
            String startDate,
            String endDate,
            String freeText,
            int page,
            int size) {

        String col = appConfig.getFailuresCollection();
        size = Math.min(size, appConfig.getMaxPageSize());

        List<Criteria> criteria = new ArrayList<>();

        if (notBlank(messageReference)) {
            String escaped = escapeRegex(messageReference);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("messageReference").regex(escaped, "i"),
                    Criteria.where("rawInput").regex("<MessageReference>\\s*" + escaped + "\\s*</MessageReference>", "i")
            ));
        }
        if (notBlank(errorCode)) {
            criteria.add(Criteria.where("errorCode").is(errorCode));
        }
        if (notBlank(stage)) {
            criteria.add(Criteria.where("stage").is(stage));
        }
        if (notBlank(inputType)) {
            criteria.add(Criteria.where("inputType").is(inputType));
        }

        if (notBlank(startDate) || notBlank(endDate)) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                if (notBlank(startDate) && notBlank(endDate)) {
                    Date start = sdf.parse(startDate);
                    Date end = new Date(sdf.parse(endDate).getTime() + 86399999L);
                    criteria.add(Criteria.where("failedAt").gte(start).lte(end));
                } else if (notBlank(startDate)) {
                    criteria.add(Criteria.where("failedAt").gte(sdf.parse(startDate)));
                } else {
                    Date end = new Date(sdf.parse(endDate).getTime() + 86399999L);
                    criteria.add(Criteria.where("failedAt").lte(end));
                }
            } catch (ParseException ignored) {
            }
        }

        if (notBlank(freeText)) {
            String escaped = escapeRegex(freeText);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("errorCode").regex(escaped, "i"),
                    Criteria.where("errorMessage").regex(escaped, "i"),
                    Criteria.where("stackTrace").regex(escaped, "i"),
                    Criteria.where("rawInput").regex(escaped, "i")
            ));
        }

        Query query = criteria.isEmpty()
                ? new Query()
                : new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));

        long total = mongoTemplate.count(query, Document.class, col);

        query.skip((long) page * size)
                .limit(size)
                .with(Sort.by(Sort.Direction.DESC, "failedAt").and(Sort.by(Sort.Direction.DESC, "_id")));

        List<Document> docs = mongoTemplate.find(query, Document.class, col);
        List<FailureDTO> rows = docs.stream().map(this::toDTO).collect(Collectors.toList());

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        PagedResponse<FailureDTO> response = new PagedResponse<FailureDTO>();
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

    public Map<String, List<String>> getDropdownOptions() {
        String col = appConfig.getFailuresCollection();
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("errorCodes", distinct("errorCode", col));
        options.put("stages", distinct("stage", col));
        options.put("inputTypes", distinct("inputType", col));
        return options;
    }

    private FailureDTO toDTO(Document doc) {
        FailureDTO dto = new FailureDTO();
        dto.setId(str(doc, "_id"));
        dto.setMessageReference(resolveMessageReference(doc));
        dto.setErrorCode(str(doc, "errorCode"));
        dto.setErrorMessage(str(doc, "errorMessage"));
        dto.setStackTrace(str(doc, "stackTrace"));
        dto.setRawInput(str(doc, "rawInput"));
        dto.setInputType(str(doc, "inputType"));
        dto.setStage(str(doc, "stage"));
        dto.setFailedAt(dateStr(doc, "failedAt"));
        return dto;
    }

    private String resolveMessageReference(Document doc) {
        String direct = str(doc, "messageReference");
        if (notBlank(direct)) {
            return direct;
        }
        String rawInput = str(doc, "rawInput");
        if (!notBlank(rawInput)) {
            return null;
        }
        Matcher matcher = MESSAGE_REFERENCE_PATTERN.matcher(rawInput);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private List<String> distinct(String field, String col) {
        try {
            return mongoTemplate.findDistinct(new Query(), field, col, Object.class)
                    .stream()
                    .filter(value -> value != null)
                    .map(Object::toString)
                    .filter(this::notBlank)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private String str(Document doc, String key) {
        Object value = doc.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text.isBlank() ? null : text;
        return value.toString();
    }

    private String dateStr(Document doc, String key) {
        Object value = doc.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text.isBlank() ? null : text;
        if (value instanceof Date date) return date.toInstant().toString();
        return value.toString();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeRegex(String input) {
        return input.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }
}
