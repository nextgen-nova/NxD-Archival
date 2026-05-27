package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.FieldConfigResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds Advanced Search field config for the split schema.
 * Known fields keep friendly labels, and nested header fields are
 * discovered dynamically as extra searchable fields.
 */
@Service
@RequiredArgsConstructor
public class FieldConfigService {

    private volatile CachedValue<List<FieldConfigResponse>> fieldConfigCache;

    private static final Map<String, Object[]> FIELD_META = new LinkedHashMap<>();

    private static final Set<String> SKIP_DISCOVERED_PARAMS = Set.of(
            "_id", "_class", "version", "messageFamily", "messageReference",
            "messageTypeCode", "messageFormat", "messageTypeDescription",
            "currentStatus", "status.current", "status.phase", "status.action", "status.reason",
            "direction", "header.direction", "header.protocol", "header.service", "header.owner",
            "header.messageTypeCode", "header.messageFormat", "header.messageReference",
            "header.transactionReference", "header.senderAddress", "header.receiverAddress",
            "header.networkChannel", "header.networkPriority", "header.workflow", "header.workflowModel",
            "header.originatorApplication", "header.processingType", "header.processPriority", "header.profileCode",
            "protocol", "networkChannel", "networkPriority", "service",
            "senderAddress", "receiverAddress", "senderName", "receiverName",
            "owner", "workflow", "workflowModel", "originatorApplication",
            "processingType", "processPriority", "profileCode",
            "ampAmount", "ampCurrency", "ampValueDate", "extractedFields.currency", "extractedFields.amount", "extractedFields.valueDate",
            "finMessagePriority", "protocolParams.messagePriority",
            "finAppId", "protocolParams.appId", "finServiceId", "protocolParams.serviceId",
            "finLogicalTerminal", "protocolParams.logicalTerminal",
            "finReceiversAddress", "protocolParams.receiversAddress",
            "historyLines", "statusHistory", "mtPayload", "mtPayload.rawFields",
            "payloadDoc.messageReference", "payloadDoc.messageId", "payloadDoc.messageTypeCode", "payloadDoc.messageFormat",
            "payloadDoc.senderAddress", "payloadDoc.receiverAddress", "payloadDoc.currency", "payloadDoc.amount",
            "payloadDoc.protocol", "payloadDoc.firstSeenAt", "payloadDoc.lastUpdatedAt",
            "payloadDoc.digest", "payloadDoc.digestAlgorithm", "payloadDoc.mtParsedPayload.rawFields",
            "payloadDoc.mtParsedPayload.rawBlock1", "payloadDoc.mtParsedPayload.rawBlock2", "payloadDoc.mtParsedPayload.rawBlock4"
    );

    private static final Map<String, String> PARAM_TO_DB = Map.ofEntries(
            Map.entry("messageType", "messageFamily"),
            Map.entry("messageCode", "header.messageTypeCode"),
            Map.entry("io", "header.direction"),
            Map.entry("status", "status.current"),
            Map.entry("phase", "status.phase"),
            Map.entry("action", "status.action"),
            Map.entry("reason", "status.reason"),
            Map.entry("messagePriority", "protocolParams.messagePriority"),
            Map.entry("finCopyService", "channel.communicationType"),
            Map.entry("possibleDuplicate", "header.pdeIndication"),
            Map.entry("creationDate", "header.dateCreated"),
            Map.entry("valueDate", "extractedFields.valueDate"),
            Map.entry("statusDate", "status.date"),
            Map.entry("receivedDate", "header.dateReceived"),
            Map.entry("sender", "header.senderAddress"),
            Map.entry("receiver", "header.receiverAddress"),
            Map.entry("senderName", "header.senderName"),
            Map.entry("receiverName", "header.receiverName"),
            Map.entry("reference", "messageReference"),
            Map.entry("transactionReference", "header.transactionReference"),
            Map.entry("mur", "protocolParams.userReference"),
            Map.entry("sessionNumber", "protocolParams.sessionNumber"),
            Map.entry("sequenceNumber", "protocolParams.sequenceNumber"),
            Map.entry("amount", "extractedFields.amount"),
            Map.entry("ccy", "extractedFields.currency"),
            Map.entry("networkProtocol", "header.protocol"),
            Map.entry("networkChannel", "header.networkChannel"),
            Map.entry("networkPriority", "header.networkPriority"),
            Map.entry("deliveryMode", "protocolParams.deliveryMode"),
            Map.entry("service", "header.service"),
            Map.entry("country", "country"),
            Map.entry("originCountry", "originCountry"),
            Map.entry("destinationCountry", "destinationCountry"),
            Map.entry("owner", "header.owner"),
            Map.entry("workflow", "header.workflow"),
            Map.entry("workflowModel", "header.workflowModel"),
            Map.entry("originatorApplication", "header.originatorApplication"),
            Map.entry("sourceSystem", "header.originatorApplication"),
            Map.entry("processingType", "header.processingType"),
            Map.entry("processPriority", "header.processPriority"),
            Map.entry("profileCode", "header.profileCode"),
            Map.entry("applicationId", "protocolParams.appId"),
            Map.entry("serviceId", "protocolParams.serviceId"),
            Map.entry("logicalTerminalAddress", "protocolParams.logicalTerminal"),
            Map.entry("historyEntity", "historyLines.entity"),
            Map.entry("historyDescription", "historyLines.comment"),
            Map.entry("historyPhase", "historyLines.phase"),
            Map.entry("historyAction", "historyLines.action"),
            Map.entry("historyUser", "historyLines.user"),
            Map.entry("historyChannel", "historyLines.channel"),
            Map.entry("block4Value", "payloadDoc.mtParsedPayload.rawBlock4"),
            Map.entry("correspondent", "payloadDoc.senderCorrespondent")
    );

    static {
        FIELD_META.put("messageType", new Object[]{"Message Format", "Classification", "select", "messageType", true});
        FIELD_META.put("messageCode", new Object[]{"Message Type / Code", "Classification", "select", "messageCode", true});
        FIELD_META.put("io", new Object[]{"Message Direction", "Classification", "select", "io", true});
        FIELD_META.put("status", new Object[]{"Status", "Classification", "select", "status", true});
        FIELD_META.put("messagePriority", new Object[]{"Message Priority", "Classification", "select", "messagePriority", false});
        FIELD_META.put("copyIndicator", new Object[]{"Copy Indicator", "Classification", "select", "copyIndicator", false});
        FIELD_META.put("finCopyService", new Object[]{"FIN-COPY Service", "Classification", "select", "finCopyService", true});
        FIELD_META.put("possibleDuplicate", new Object[]{"PDE Indication", "Classification", "boolean", "possibleDuplicate", true});

        FIELD_META.put("creationDate", new Object[]{"Creation Date Range", "Date & Time", "date-range", "startDate,endDate", true});
        FIELD_META.put("valueDate", new Object[]{"Value Date Range", "Date & Time", "date-range2", "valueDateFrom,valueDateTo", true});
        FIELD_META.put("statusDate", new Object[]{"Status Date Range", "Date & Time", "date-range2", "statusDateFrom,statusDateTo", false});
        FIELD_META.put("receivedDate", new Object[]{"Received Date Range", "Date & Time", "date-range2", "receivedDateFrom,receivedDateTo", false});

        FIELD_META.put("sender", new Object[]{"Sender BIC", "Parties", "text", "sender", true});
        FIELD_META.put("receiver", new Object[]{"Receiver BIC", "Parties", "text", "receiver", true});
        FIELD_META.put("senderName", new Object[]{"Sender Institution", "Parties", "text", "senderName", false});
        FIELD_META.put("receiverName", new Object[]{"Receiver Institution", "Parties", "text", "receiverName", false});
        FIELD_META.put("correspondent", new Object[]{"Correspondent", "Parties", "text", "correspondent", true});

        FIELD_META.put("reference", new Object[]{"Message Reference", "References", "text", "reference", true});
        FIELD_META.put("transactionReference", new Object[]{"Transaction Reference", "References", "text", "transactionReference", false});
        FIELD_META.put("mur", new Object[]{"MUR (Tag 20)", "References", "text", "mur", true});
        FIELD_META.put("sessionNumber", new Object[]{"Session No.", "References", "text", "sessionNumber", true});
        FIELD_META.put("sequenceNumber", new Object[]{"Sequence No. Range", "References", "seq-range", "seqFrom,seqTo", true});

        FIELD_META.put("amount", new Object[]{"Amount Range", "Financial", "amount-range", "amountFrom,amountTo", true});
        FIELD_META.put("ccy", new Object[]{"Currency (CCY)", "Financial", "select", "ccy", true});

        FIELD_META.put("networkProtocol", new Object[]{"Network Protocol", "Routing", "select", "networkProtocol", true});
        FIELD_META.put("networkChannel", new Object[]{"Network Channel", "Routing", "select", "networkChannel", true});
        FIELD_META.put("networkPriority", new Object[]{"Network Priority", "Routing", "select", "networkPriority", false});
        FIELD_META.put("deliveryMode", new Object[]{"Delivery Mode", "Routing", "select", "deliveryMode", true});
        FIELD_META.put("service", new Object[]{"Service", "Routing", "select", "service", true});
        FIELD_META.put("country", new Object[]{"Country", "Geography", "select", "country", false});
        FIELD_META.put("originCountry", new Object[]{"Origin Country", "Geography", "select", "originCountry", false});
        FIELD_META.put("destinationCountry", new Object[]{"Destination Country", "Geography", "select", "destinationCountry", false});

        FIELD_META.put("owner", new Object[]{"Owner / Unit", "Ownership", "select", "owner", true});
        FIELD_META.put("workflow", new Object[]{"Workflow", "Ownership", "select", "workflow", false});
        FIELD_META.put("workflowModel", new Object[]{"Workflow Model", "Ownership", "select", "workflowModel", true});
        FIELD_META.put("originatorApplication", new Object[]{"Originator Application", "Ownership", "select", "originatorApplication", false});
        FIELD_META.put("sourceSystem", new Object[]{"Source System", "Ownership", "select", "sourceSystem", true});

        FIELD_META.put("phase", new Object[]{"Phase", "Lifecycle", "select", "phase", true});
        FIELD_META.put("action", new Object[]{"Action", "Lifecycle", "select", "action", true});
        FIELD_META.put("reason", new Object[]{"Reason", "Lifecycle", "select", "reason", true});

        FIELD_META.put("processingType", new Object[]{"Processing Type", "Processing", "select", "processingType", true});
        FIELD_META.put("processPriority", new Object[]{"Process Priority", "Processing", "select", "processPriority", false});
        FIELD_META.put("profileCode", new Object[]{"Profile Code", "Processing", "select", "profileCode", false});

        FIELD_META.put("applicationId", new Object[]{"Application ID", "FIN Header", "text", "applicationId", false});
        FIELD_META.put("serviceId", new Object[]{"Service ID", "FIN Header", "text", "serviceId", false});
        FIELD_META.put("logicalTerminalAddress", new Object[]{"Logical Terminal", "FIN Header", "text", "logicalTerminalAddress", false});

        FIELD_META.put("historyEntity", new Object[]{"History Entity", "History", "text", "historyEntity", false});
        FIELD_META.put("historyDescription", new Object[]{"History Comment", "History", "text", "historyDescription", false});
        FIELD_META.put("historyPhase", new Object[]{"History Phase", "History", "select", "historyPhase", false});
        FIELD_META.put("historyAction", new Object[]{"History Action", "History", "select", "historyAction", false});
        FIELD_META.put("historyUser", new Object[]{"History User", "History", "text", "historyUser", false});
        FIELD_META.put("historyChannel", new Object[]{"History Channel", "History", "text", "historyChannel", false});

    }

    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;

    public List<FieldConfigResponse> getFieldConfig() {
        CachedValue<List<FieldConfigResponse>> cache = fieldConfigCache;
        if (cache != null && !cache.isExpired(appConfig.getMetadataCacheTtlMs())) {
            return cache.value();
        }

        String messagesCol = appConfig.getSwiftCollection();
        String payloadsCol = appConfig.getPayloadsCollection();

        List<FieldConfigResponse> result = new ArrayList<>();
        Set<String> handledBackendParams = new LinkedHashSet<>();

        for (Map.Entry<String, Object[]> entry : FIELD_META.entrySet()) {
            String key = entry.getKey();
            Object[] meta = entry.getValue();
            String label = (String) meta[0];
            String group = (String) meta[1];
            String type = (String) meta[2];
            String backendParam = (String) meta[3];
            boolean showInTable = (Boolean) meta[4];

            List<String> options = Collections.emptyList();
            if ("select".equals(type)) {
                options = distinctValues(resolveDbField(backendParam), messagesCol, payloadsCol);
            }

            result.add(new FieldConfigResponse(
                    key, label, group, type, options, backendParam,
                    showInTable ? label : null, showInTable
            ));
            handledBackendParams.add(resolveDbField(backendParam));
        }

        result.add(new FieldConfigResponse(
                "freeSearch", "Free Search Text", "Other", "text-wide",
                Collections.emptyList(), "freeSearchText", null, false
        ));
        handledBackendParams.add("freeSearchText");

        discoverMessageLeafPaths(messagesCol).forEach(path -> {
            if (handledBackendParams.contains(path) || SKIP_DISCOVERED_PARAMS.contains(path)) {
                return;
            }
            result.add(buildDiscoveredField(path, path, messagesCol, payloadsCol, "Message"));
        });

        List<FieldConfigResponse> immutable = List.copyOf(result);
        fieldConfigCache = new CachedValue<>(immutable, System.currentTimeMillis());
        return immutable;
    }

    private FieldConfigResponse buildDiscoveredField(String key, String backendParam,
                                                     String messagesCol, String payloadsCol, String group) {
        List<String> options = distinctValues(backendParam, messagesCol, payloadsCol);
        boolean smallSelect = !options.isEmpty() && options.size() <= 50;
        return new FieldConfigResponse(
                key,
                toPrettyLabel(key),
                group,
                smallSelect ? "select" : "text",
                smallSelect ? options : Collections.emptyList(),
                backendParam,
                null,
                false
        );
    }

    private Set<String> discoverMessageLeafPaths(String collection) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            List<Document> docs = mongoTemplate.find(new Query().limit(appConfig.getFieldDiscoverySampleSize()), Document.class, collection);
            for (Document doc : docs) {
                collectLeafPaths(doc, "", paths);
            }
        } catch (Exception ignored) {
        }
        return paths;
    }

    private Set<String> discoverPayloadLeafPaths(String collection) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            List<Document> docs = mongoTemplate.find(new Query().limit(appConfig.getFieldDiscoverySampleSize()), Document.class, collection);
            for (Document doc : docs) {
                collectLeafPaths(doc, "", paths);
            }
        } catch (Exception ignored) {
        }
        return paths;
    }

    private record CachedValue<T>(T value, long loadedAtMs) {
        private boolean isExpired(long ttlMs) {
            return ttlMs <= 0 || (System.currentTimeMillis() - loadedAtMs) > ttlMs;
        }
    }

    private void collectLeafPaths(Document document, String prefix, Set<String> paths) {
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if ("_class".equals(key) || "version".equals(key)) {
                continue;
            }

            Object value = entry.getValue();
            String path = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Document nested) {
                if (isWrapperDocument(nested)) {
                    paths.add(path);
                } else {
                    collectLeafPaths(nested, path, paths);
                }
                continue;
            }

            if (value instanceof List<?>) {
                continue;
            }

            paths.add(path);
        }
    }

    private boolean isWrapperDocument(Document document) {
        return document.size() == 1 && (document.containsKey("$oid") || document.containsKey("$date") || document.containsKey("$numberLong"));
    }

    private List<String> distinctValues(String backendParam, String messagesCol, String payloadsCol) {
        String dbField = resolveDbField(backendParam);
        boolean payloadField = dbField.startsWith("payloadDoc.");
        String collection = payloadField ? payloadsCol : messagesCol;
        String fieldPath = payloadField ? dbField.substring("payloadDoc.".length()) : dbField;

        try {
            return mongoTemplate.findDistinct(new Query(), fieldPath, collection, String.class).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private String resolveDbField(String backendParam) {
        return PARAM_TO_DB.getOrDefault(backendParam, backendParam);
    }

    private String toPrettyLabel(String key) {
        String normalized = key.replace("payload.", "Payload ").replace('.', ' ').replace('_', ' ').replace('-', ' ');
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (i > 0 && Character.isUpperCase(current) && Character.isLowerCase(normalized.charAt(i - 1))) {
                label.append(' ');
            }
            label.append(current);
        }
        String collapsed = label.toString().trim().replaceAll("\\s+", " ");
        String[] words = collapsed.split(" ");
        StringBuilder titled = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!titled.isEmpty()) {
                titled.append(' ');
            }
            titled.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                titled.append(word.substring(1));
            }
        }
        return titled.toString();
    }
}
