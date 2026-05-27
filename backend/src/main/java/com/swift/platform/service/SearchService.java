package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.DropdownOptionsResponse;
import com.swift.platform.dto.ExportColumnRequest;
import com.swift.platform.dto.PagedResponse;
import com.swift.platform.dto.SearchResponse;
import com.mongodb.client.model.ReplaceOptions;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import com.mongodb.client.FindIterable;
import org.xml.sax.InputSource;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String PAYLOAD_ALIAS = "payloadDoc";
    private static final int DROPDOWN_SNAPSHOT_VERSION = 2;
    private static final int EXCEL_HARD_MAX_ROWS_PER_SHEET = 1_048_575;
    private static final int EXCEL_MAX_CELL_CHARS = 32_767;
    private static final Pattern EXCEL_ILLEGAL_XML_CHARS = Pattern.compile("[^\\x09\\x0A\\x0D\\x20-\\uD7FF\\uE000-\\uFFFD]");
    private static final Pattern TEXT_PAYLOAD_TAG_LINE_PATTERN = Pattern.compile("^\\s*(.*?)\\s*:(\\d{2}[A-Z]?):\\s*(.*)$");
    private static final Pattern GENERIC_TEXT_PAYLOAD_LABEL_PATTERN = Pattern.compile("(?i)^line\\s+\\d+.*$");
    private static final Pattern MX_INDEX_SUFFIX_PATTERN = Pattern.compile("\\[\\d+\\]$");
    private static final Map<String, Field> SEARCH_RESPONSE_FIELDS = initSearchResponseFields();
    private static final Document SEARCH_SORT = new Document("header.dateCreated", -1).append("dateCreated", -1).append("_id", -1);
    private static final Set<String> LOOKUP_REQUIRED_PARAMS = Set.of(
            "mur", "correspondent", "block4Tag", "block4Value", "freeSearchText", "uetr", "sender", "receiver"
    );

    private static final Set<String> HANDLED_PARAMS = Set.of(
            "messageType", "messageCode", "io", "status", "phase", "action", "reason", "messagePriority",
            "networkProtocol", "networkChannel", "networkPriority", "deliveryMode", "service",
            "backendChannelProtocol", "backendChannelCode", "finCopyService",
            "owner", "workflow", "workflowModel", "originatorApplication", "sourceSystem",
            "processingType", "processPriority", "profileCode",
            "ccy", "sender", "receiver", "correspondent",
            "possibleDuplicate", "digestMCheckResult", "digest2CheckResult",
            "reference", "transactionReference", "transferReference", "relatedReference", "mur", "uetr",
            "mxInputReference", "mxOutputReference", "networkReference", "e2eMessageId", "amlDetails",
            "logicalTerminalAddress", "applicationId", "serviceId", "finReceiversAddress",
            "startDate", "endDate", "valueDateFrom", "valueDateTo",
            "statusDateFrom", "statusDateTo", "receivedDateFrom", "receivedDateTo",
            "amountFrom", "amountTo", "seqFrom", "seqTo", "sessionNumber",
            "historyEntity", "historyDescription", "historyPhase", "historyAction", "historyUser", "historyChannel",
            "block4Tag", "block4Value", "freeSearchText", "page", "size"
    );

    private static final Map<String, String> FIN_TAG_LABELS = Map.ofEntries(
            Map.entry("20", "Transaction Reference Number"),
            Map.entry("21", "Related Reference"),
            Map.entry("23B", "Bank Operation Code"),
            Map.entry("32A", "Value Date / Currency / Interbank Settled Amount"),
            Map.entry("33B", "Currency / Instructed Amount"),
            Map.entry("50A", "Ordering Customer (BIC)"),
            Map.entry("50F", "Ordering Customer (Structured)"),
            Map.entry("50K", "Ordering Customer"),
            Map.entry("52A", "Ordering Institution (BIC)"),
            Map.entry("52D", "Ordering Institution"),
            Map.entry("53A", "Sender's Correspondent (BIC)"),
            Map.entry("53B", "Sender's Correspondent (Location)"),
            Map.entry("53D", "Sender's Correspondent"),
            Map.entry("56A", "Intermediary Institution (BIC)"),
            Map.entry("57A", "Account With Institution (BIC)"),
            Map.entry("57C", "Account With Institution (Account)"),
            Map.entry("57D", "Account With Institution"),
            Map.entry("59", "Beneficiary Customer"),
            Map.entry("59A", "Beneficiary Customer (BIC)"),
            Map.entry("70", "Remittance Information"),
            Map.entry("71A", "Details of Charges"),
            Map.entry("71F", "Sender's Charges"),
            Map.entry("71G", "Receiver's Charges"),
            Map.entry("72", "Sender to Receiver Information")
    );
    private static final Map<String, String> FIN_BLOCK3_LABELS = Map.ofEntries(
            Map.entry("103", "Service Identifier"),
            Map.entry("106", "MIR"),
            Map.entry("108", "MUR"),
            Map.entry("111", "Service Type Identifier"),
            Map.entry("113", "Banking Priority"),
            Map.entry("115", "Addressee Information"),
            Map.entry("116", "Value Date"),
            Map.entry("119", "Validation Flag"),
            Map.entry("121", "UETR"),
            Map.entry("165", "End-to-End Reference"),
            Map.entry("423", "Balance Check"),
            Map.entry("424", "Related Reference"),
            Map.entry("433", "Sanctions Screening Info"),
            Map.entry("434", "Payment Controls"),
            Map.entry("435", "Charges Information"),
            Map.entry("436", "Settlement Information"),
            Map.entry("451", "Linked Message Reference"),
            Map.entry("455", "Market Infrastructure ID"),
            Map.entry("504", "Service Data"),
            Map.entry("512", "Batch/Group Reference"),
            Map.entry("600", "Transport Information")
    );

    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;
    private volatile CachedValue<Long> unfilteredCountCache;

    public DropdownOptionsResponse getDropdownOptions() {
        DropdownOptionsResponse response = readDropdownOptionsSnapshot();
        return response != null ? response : refreshDropdownOptionsSnapshot();
    }

    @Scheduled(
            fixedDelayString = "#{T(java.time.Duration).ofHours(${search.dropdown.refresh-interval-hours:6}).toMillis()}",
            initialDelayString = "#{T(java.time.Duration).ofMinutes(${search.dropdown.refresh-initial-delay-minutes:5}).toMillis()}"
    )
    public void refreshDropdownOptionsSnapshotOnSchedule() {
        if (!appConfig.isDropdownRefreshEnabled()) {
            return;
        }
        refreshDropdownOptionsSnapshot();
    }

    public DropdownOptionsResponse refreshDropdownOptionsSnapshot() {
        DropdownOptionsResponse response = buildFreshDropdownOptions();
        saveDropdownOptionsSnapshot(response);
        return response;
    }

    private DropdownOptionsResponse buildFreshDropdownOptions() {
        String messagesCol = appConfig.getSwiftCollection();
        String payloadsCol = appConfig.getPayloadsCollection();

        DropdownOptionsResponse res = new DropdownOptionsResponse();
        res.setFormats(Arrays.asList("MT", "MX"));

        List<String> mtCodes = distinctMessageCodesByFamily(messagesCol, "MT");
        List<String> mxCodes = distinctMessageCodesByFamily(messagesCol, "MX");
        LinkedHashSet<String> mergedCodes = new LinkedHashSet<>();
        mergedCodes.addAll(mtCodes);
        mergedCodes.addAll(mxCodes);
        mergedCodes.addAll(distinctMerged(messagesCol, "messageTypeCode", "header.messageTypeCode"));
        List<String> codes = mergedCodes.stream().sorted().collect(Collectors.toList());

        res.setMessageCodes(codes);
        res.setTypes(codes);
        res.setMtTypes(mtCodes);
        res.setMxTypes(mxCodes);
        res.setAllMtMxTypes(Collections.emptyList());

        res.setStatuses(distinctMerged(messagesCol, "currentStatus", "status.current"));
        res.setPhases(distinctMerged(messagesCol, "statusPhase", "status.phase"));
        res.setActions(distinctMerged(messagesCol, "statusAction", "status.action"));
        res.setIoDirections(distinctMerged(messagesCol, "direction", "header.direction"));
        res.setDirections(distinctMerged(messagesCol, "direction", "header.direction"));
        res.setReasons(distinctMerged(messagesCol, "statusReason", "status.reason"));

        res.setNetworkProtocols(distinctMerged(messagesCol, "protocol", "header.protocol"));
        res.setNetworks(distinctMerged(messagesCol, "protocol", "header.protocol"));
        res.setNetworkChannels(distinctMerged(messagesCol, "networkChannel", "header.networkChannel"));
        res.setBackendChannels(distinctMerged(messagesCol, "header.backendChannel"));
        res.setNetworkPriorities(distinctMerged(messagesCol, "networkPriority", "header.networkPriority"));
        res.setNetworkStatuses(Collections.emptyList());
        res.setDeliveryModes(distinctMerged(messagesCol, "communicationType", "channel.communicationType"));
        res.setServices(distinctMerged(messagesCol, "service", "header.service"));
        res.setSenders(distinctBics(messagesCol, payloadsCol, true));
        res.setReceivers(distinctBics(messagesCol, payloadsCol, false));
        List<String> owners = distinctMerged(messagesCol, "owner", "header.owner");
        res.setOwners(owners);
        res.setOwnerUnits(owners);

        res.setCountries(Collections.emptyList());
        res.setOriginCountries(Collections.emptyList());
        res.setDestinationCountries(Collections.emptyList());

        res.setWorkflows(distinctMerged(messagesCol, "workflow", "header.workflow"));
        res.setWorkflowModels(distinctMerged(messagesCol, "workflowModel", "header.workflowModel"));
        res.setSourceSystems(distinctMerged(messagesCol, "originatorApplication", "header.originatorApplication"));
        res.setOriginatorApplications(distinctMerged(messagesCol, "originatorApplication", "header.originatorApplication"));

        List<String> currencies = new ArrayList<>(distinctMerged(messagesCol, "ampCurrency", "extractedFields.currency"));
        currencies.addAll(distinct(payloadsCol, "currency"));
        res.setCurrencies(currencies.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().sorted().collect(Collectors.toList()));

        res.setProcessingTypes(distinctMerged(messagesCol, "processingType", "header.processingType"));
        res.setProcessPriorities(distinctMerged(messagesCol, "processPriority", "header.processPriority"));
        res.setProfileCodes(distinctMerged(messagesCol, "profileCode", "header.profileCode"));
        res.setEnvironments(Collections.emptyList());

        res.setAmlStatuses(Collections.emptyList());
        List<String> finCopyValues = distinctMerged(messagesCol, "channel.communicationType");
        res.setFinCopies(finCopyValues);
        res.setFinCopyServices(finCopyValues);
        res.setMessagePriorities(distinctMerged(messagesCol, "finMessagePriority", "protocolParams.messagePriority"));
        res.setNackCodes(Collections.emptyList());
        res.setCopyIndicators(Collections.emptyList());
        return res;
    }

    private DropdownOptionsResponse readDropdownOptionsSnapshot() {
        try {
            List<DropdownFieldSpec> specs = dropdownFieldSpecs();
            List<String> snapshotIds = specs.stream()
                    .map(spec -> buildDropdownSnapshotId(spec.key()))
                    .collect(Collectors.toList());

            List<Document> docs = mongoTemplate.getCollection(appConfig.getDropdownOptionsCollection())
                    .find(new Document("_id", new Document("$in", snapshotIds)))
                    .into(new ArrayList<>());

            if (docs.size() < specs.size()) {
                return null;
            }

            Map<String, List<String>> valuesByField = new HashMap<>();
            for (Document doc : docs) {
                Object snapshotVersion = doc.get("schemaVersion");
                if (!(snapshotVersion instanceof Number number) || number.intValue() != DROPDOWN_SNAPSHOT_VERSION) {
                    return null;
                }
                String field = firstNonBlank(doc.getString("field"), extractFieldKeyFromSnapshotId(doc.getString("_id")));
                if (notBlank(field)) {
                    valuesByField.put(field, normalizeDropdownValues(toStringList(doc.get("values"))));
                }
            }

            if (specs.stream().map(DropdownFieldSpec::key).anyMatch(field -> !valuesByField.containsKey(field))) {
                return null;
            }

            DropdownOptionsResponse response = new DropdownOptionsResponse();
            for (DropdownFieldSpec spec : specs) {
                spec.setter().accept(response, valuesByField.getOrDefault(spec.key(), Collections.emptyList()));
            }
            return response;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveDropdownOptionsSnapshot(DropdownOptionsResponse response) {
        if (response == null) {
            return;
        }

        ReplaceOptions upsert = new ReplaceOptions().upsert(true);
        String collectionName = appConfig.getDropdownOptionsCollection();
        String groupId = appConfig.getDropdownOptionsDocumentId();
        String updatedAt = Instant.now().toString();
        List<String> activeSnapshotIds = dropdownFieldSpecs().stream()
                .map(spec -> buildDropdownSnapshotId(spec.key()))
                .collect(Collectors.toList());

        mongoTemplate.getCollection(collectionName).deleteMany(new Document("groupId", groupId)
                .append("_id", new Document("$nin", activeSnapshotIds)));

        for (DropdownFieldSpec spec : dropdownFieldSpecs()) {
            List<String> values = normalizeDropdownValues(spec.getter().apply(response));
            Document doc = new Document("_id", buildDropdownSnapshotId(spec.key()))
                    .append("groupId", groupId)
                    .append("schemaVersion", DROPDOWN_SNAPSHOT_VERSION)
                    .append("field", spec.key())
                    .append("values", values)
                    .append("updatedAt", updatedAt);
            mongoTemplate.getCollection(collectionName)
                    .replaceOne(new Document("_id", doc.getString("_id")), doc, upsert);
        }
    }

    private String buildDropdownSnapshotId(String fieldKey) {
        return appConfig.getDropdownOptionsDocumentId() + ":" + fieldKey;
    }

    private String extractFieldKeyFromSnapshotId(String snapshotId) {
        if (!notBlank(snapshotId)) {
            return null;
        }
        int separatorIndex = snapshotId.indexOf(':');
        if (separatorIndex < 0 || separatorIndex >= snapshotId.length() - 1) {
            return snapshotId;
        }
        return snapshotId.substring(separatorIndex + 1);
    }

    private List<String> normalizeDropdownValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(this::notBlank)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .sorted()
                .collect(Collectors.toList());
    }

    private List<DropdownFieldSpec> dropdownFieldSpecs() {
        return List.of(
                new DropdownFieldSpec("formats", DropdownOptionsResponse::setFormats, DropdownOptionsResponse::getFormats),
                new DropdownFieldSpec("messageCodes", DropdownOptionsResponse::setMessageCodes, DropdownOptionsResponse::getMessageCodes),
                new DropdownFieldSpec("types", DropdownOptionsResponse::setTypes, DropdownOptionsResponse::getTypes),
                new DropdownFieldSpec("mtTypes", DropdownOptionsResponse::setMtTypes, DropdownOptionsResponse::getMtTypes),
                new DropdownFieldSpec("mxTypes", DropdownOptionsResponse::setMxTypes, DropdownOptionsResponse::getMxTypes),
                new DropdownFieldSpec("allMtMxTypes", DropdownOptionsResponse::setAllMtMxTypes, DropdownOptionsResponse::getAllMtMxTypes),
                new DropdownFieldSpec("statuses", DropdownOptionsResponse::setStatuses, DropdownOptionsResponse::getStatuses),
                new DropdownFieldSpec("phases", DropdownOptionsResponse::setPhases, DropdownOptionsResponse::getPhases),
                new DropdownFieldSpec("actions", DropdownOptionsResponse::setActions, DropdownOptionsResponse::getActions),
                new DropdownFieldSpec("ioDirections", DropdownOptionsResponse::setIoDirections, DropdownOptionsResponse::getIoDirections),
                new DropdownFieldSpec("directions", DropdownOptionsResponse::setDirections, DropdownOptionsResponse::getDirections),
                new DropdownFieldSpec("networkProtocols", DropdownOptionsResponse::setNetworkProtocols, DropdownOptionsResponse::getNetworkProtocols),
                new DropdownFieldSpec("networks", DropdownOptionsResponse::setNetworks, DropdownOptionsResponse::getNetworks),
                new DropdownFieldSpec("networkChannels", DropdownOptionsResponse::setNetworkChannels, DropdownOptionsResponse::getNetworkChannels),
                new DropdownFieldSpec("backendChannels", DropdownOptionsResponse::setBackendChannels, DropdownOptionsResponse::getBackendChannels),
                new DropdownFieldSpec("networkPriorities", DropdownOptionsResponse::setNetworkPriorities, DropdownOptionsResponse::getNetworkPriorities),
                new DropdownFieldSpec("networkStatuses", DropdownOptionsResponse::setNetworkStatuses, DropdownOptionsResponse::getNetworkStatuses),
                new DropdownFieldSpec("deliveryModes", DropdownOptionsResponse::setDeliveryModes, DropdownOptionsResponse::getDeliveryModes),
                new DropdownFieldSpec("services", DropdownOptionsResponse::setServices, DropdownOptionsResponse::getServices),
                new DropdownFieldSpec("senders", DropdownOptionsResponse::setSenders, DropdownOptionsResponse::getSenders),
                new DropdownFieldSpec("receivers", DropdownOptionsResponse::setReceivers, DropdownOptionsResponse::getReceivers),
                new DropdownFieldSpec("owners", DropdownOptionsResponse::setOwners, DropdownOptionsResponse::getOwners),
                new DropdownFieldSpec("ownerUnits", DropdownOptionsResponse::setOwnerUnits, DropdownOptionsResponse::getOwnerUnits),
                new DropdownFieldSpec("countries", DropdownOptionsResponse::setCountries, DropdownOptionsResponse::getCountries),
                new DropdownFieldSpec("originCountries", DropdownOptionsResponse::setOriginCountries, DropdownOptionsResponse::getOriginCountries),
                new DropdownFieldSpec("destinationCountries", DropdownOptionsResponse::setDestinationCountries, DropdownOptionsResponse::getDestinationCountries),
                new DropdownFieldSpec("workflows", DropdownOptionsResponse::setWorkflows, DropdownOptionsResponse::getWorkflows),
                new DropdownFieldSpec("workflowModels", DropdownOptionsResponse::setWorkflowModels, DropdownOptionsResponse::getWorkflowModels),
                new DropdownFieldSpec("sourceSystems", DropdownOptionsResponse::setSourceSystems, DropdownOptionsResponse::getSourceSystems),
                new DropdownFieldSpec("originatorApplications", DropdownOptionsResponse::setOriginatorApplications, DropdownOptionsResponse::getOriginatorApplications),
                new DropdownFieldSpec("currencies", DropdownOptionsResponse::setCurrencies, DropdownOptionsResponse::getCurrencies),
                new DropdownFieldSpec("processingTypes", DropdownOptionsResponse::setProcessingTypes, DropdownOptionsResponse::getProcessingTypes),
                new DropdownFieldSpec("processPriorities", DropdownOptionsResponse::setProcessPriorities, DropdownOptionsResponse::getProcessPriorities),
                new DropdownFieldSpec("profileCodes", DropdownOptionsResponse::setProfileCodes, DropdownOptionsResponse::getProfileCodes),
                new DropdownFieldSpec("environments", DropdownOptionsResponse::setEnvironments, DropdownOptionsResponse::getEnvironments),
                new DropdownFieldSpec("amlStatuses", DropdownOptionsResponse::setAmlStatuses, DropdownOptionsResponse::getAmlStatuses),
                new DropdownFieldSpec("finCopies", DropdownOptionsResponse::setFinCopies, DropdownOptionsResponse::getFinCopies),
                new DropdownFieldSpec("finCopyServices", DropdownOptionsResponse::setFinCopyServices, DropdownOptionsResponse::getFinCopyServices),
                new DropdownFieldSpec("messagePriorities", DropdownOptionsResponse::setMessagePriorities, DropdownOptionsResponse::getMessagePriorities),
                new DropdownFieldSpec("nackCodes", DropdownOptionsResponse::setNackCodes, DropdownOptionsResponse::getNackCodes),
                new DropdownFieldSpec("copyIndicators", DropdownOptionsResponse::setCopyIndicators, DropdownOptionsResponse::getCopyIndicators),
                new DropdownFieldSpec("reasons", DropdownOptionsResponse::setReasons, DropdownOptionsResponse::getReasons)
        );
    }

    public PagedResponse<SearchResponse> search(Map<String, String> filters, int page, int size) {
        return search(filters, page, size, null, null);
    }

    public PagedResponse<SearchResponse> search(Map<String, String> filters, int page, int size, String cursor, Boolean countExactOverride) {
        String messagesCol = appConfig.getSwiftCollection();
        int pageSize = Math.min(size, appConfig.getMaxPageSize());

        int safePage = Math.max(page, 0);
        String safeCursor = notBlank(cursor) ? cursor : null;
        boolean exactCount = Boolean.TRUE.equals(countExactOverride) || (safePage == 0 && !notBlank(safeCursor));
        SearchPlan plan = buildSearchPlan(filters);

        long total = -1L;
        List<SearchResponse> rows;
        boolean hasNext;
        String nextCursor;
        if (appConfig.isOptimizeWithoutLookup() && !plan.requiresLookup()) {
            if (exactCount) {
                total = countMessages(messagesCol, plan.messageMatch(), filters);
            }
            PageSlice<Document> pageSlice = findMessagePage(messagesCol, plan.messageMatch(), safePage, pageSize, safeCursor);
            List<Document> docs = pageSlice.items();
            Map<String, Document> payloadByReference = fetchPayloadsByReference(docs);
            rows = docs.stream()
                    .map(doc -> toSearchRowResponse(withPayloadDoc(doc, payloadByReference.get(stringValue(doc.get("messageReference"))))))
                    .collect(Collectors.toList());
            hasNext = pageSlice.hasNext();
            nextCursor = pageSlice.nextCursor();
        } else {
            if (exactCount) {
                total = aggregateCount(messagesCol, buildLookupPipeline(plan.messageMatch(), plan.postLookupMatch()), filters);
            }
            PageSlice<Document> pageSlice = findLookupPage(messagesCol, plan, safePage, pageSize, safeCursor);
            List<Document> docs = pageSlice.items();
            rows = docs.stream().map(this::toSearchRowResponse).collect(Collectors.toList());
            hasNext = pageSlice.hasNext();
            nextCursor = pageSlice.nextCursor();
        }

        int totalPages = exactCount && pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        PagedResponse<SearchResponse> response = new PagedResponse<SearchResponse>();
        response.setContent(rows);
        response.setTotalElements(total);
        response.setTotalPages(totalPages);
        response.setPageNumber(safePage);
        response.setPageSize(pageSize);
        response.setFirst(safePage == 0);
        response.setLast(!hasNext);
        response.setTotalExact(exactCount);
        response.setHasNext(hasNext);
        response.setNextCursor(nextCursor);
        return response;
    }

    public List<SearchResponse> searchAllForExport(Map<String, String> filters) {
        List<SearchResponse> results = new ArrayList<>();
        forEachExportResponse(filters, results::add);
        return results;
    }

    public long countSearchResults(Map<String, String> filters) {
        String messagesCol = appConfig.getSwiftCollection();
        SearchPlan plan = buildSearchPlan(filters);
        if (appConfig.isOptimizeWithoutLookup() && !plan.requiresLookup()) {
            return countMessages(messagesCol, plan.messageMatch(), filters);
        }
        return aggregateCount(messagesCol, buildLookupPipeline(plan), filters);
    }

    public void forEachDetailedExportResponse(Map<String, String> filters, Consumer<SearchResponse> consumer) {
        forEachExportResponse(filters, consumer);
    }

    public void streamResultTableExport(Map<String, String> filters,
                                        List<ExportColumnRequest> columns,
                                        String format,
                                        OutputStream outputStream) throws IOException {
        List<ExportColumnRequest> safeColumns = normalizeExportColumns(columns);
        if ("excel".equalsIgnoreCase(format)) {
            streamResultTableExcel(filters, safeColumns, outputStream);
            return;
        }
        streamResultTableCsv(filters, safeColumns, outputStream);
    }

    public SearchResponse getMessageDetail(String reference) {
        if (!notBlank(reference)) {
            return null;
        }
        Document match = new Document("$or", List.of(
                new Document("messageReference", reference),
                new Document("header.messageReference", reference)
        ));
        List<Document> pipeline = buildLookupPipeline(new SearchPlan(match, new Document(), true));
        pipeline.add(new Document("$limit", 1));
        List<Document> docs = aggregate(appConfig.getSwiftCollection(), pipeline);
        return docs.isEmpty() ? null : toResponse(docs.get(0));
    }

    public List<SearchResponse> getMessageDetailsByReferences(List<String> references) {
        List<String> uniqueReferences = references == null ? Collections.emptyList() : references.stream()
                .filter(this::notBlank)
                .distinct()
                .collect(Collectors.toList());
        if (uniqueReferences.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> messageDocs = mongoTemplate.getCollection(appConfig.getSwiftCollection())
                .find(new Document("messageReference", new Document("$in", uniqueReferences)))
                .into(new ArrayList<>());
        Map<String, Document> payloadByReference = fetchPayloadsByReference(messageDocs);
        return messageDocs.stream()
                .map(doc -> toResponse(withPayloadDoc(doc, payloadByReference.get(stringValue(doc.get("messageReference"))))))
                .collect(Collectors.toList());
    }

    private List<Document> buildLookupPipeline(SearchPlan plan) {
        return buildLookupPipeline(plan.messageMatch(), plan.postLookupMatch());
    }

    private List<Document> buildLookupPipeline(Document messageMatch, Document postLookupMatch) {
        List<Document> pipeline = new ArrayList<>();
        if (messageMatch != null && !messageMatch.isEmpty()) {
            pipeline.add(new Document("$match", messageMatch));
        }
        pipeline.add(new Document("$lookup",
                new Document("from", appConfig.getPayloadsCollection())
                        .append("localField", "messageReference")
                        .append("foreignField", "messageReference")
                        .append("as", PAYLOAD_ALIAS)
        ));
        pipeline.add(new Document("$unwind",
                new Document("path", "$" + PAYLOAD_ALIAS)
                        .append("preserveNullAndEmptyArrays", true)
        ));

        if (postLookupMatch != null && !postLookupMatch.isEmpty()) {
            pipeline.add(new Document("$match", postLookupMatch));
        }
        return pipeline;
    }

    private SearchPlan buildSearchPlan(Map<String, String> filters) {
        boolean requiresLookup = requiresPayloadLookup(filters);
        if (!requiresLookup) {
            return new SearchPlan(buildMessageOnlyMatch(filters), new Document(), false);
        }
        return new SearchPlan(buildMessageOnlyMatch(filters), buildMatch(filters), true);
    }

    private boolean requiresPayloadLookup(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        return filters.keySet().stream().anyMatch(param -> LOOKUP_REQUIRED_PARAMS.contains(param) || !HANDLED_PARAMS.contains(param));
    }

    private Document buildMessageOnlyMatch(Map<String, String> filters) {
        List<Document> clauses = new ArrayList<>();

        addMessageFormatFilter(clauses, filters, false);
        messageCodePrefixIfAny(clauses, filters, "messageCode", "messageTypeCode", "header.messageTypeCode");
        exactIfAny(clauses, filters, "io", "direction", "header.direction");
        exactIfAny(clauses, filters, "status", "currentStatus", "status.current");
        exactIfAny(clauses, filters, "phase", "statusPhase", "status.phase");
        exactIfAny(clauses, filters, "action", "statusAction", "status.action");
        exactIfAny(clauses, filters, "reason", "statusReason", "status.reason");
        exactIfAny(clauses, filters, "messagePriority", "finMessagePriority", "protocolParams.messagePriority");

        exactIfAny(clauses, filters, "networkProtocol", "protocol", "header.protocol");
        exactIfAny(clauses, filters, "networkChannel", "header.backendChannel");
        exactIfAny(clauses, filters, "networkPriority", "networkPriority", "header.networkPriority");
        exactIfAny(clauses, filters, "deliveryMode", "protocolParams.deliveryMode", "payloadDoc.deliveryMode");
        exactIfAny(clauses, filters, "service", "service", "header.service");
        exactIfAny(clauses, filters, "backendChannelProtocol", "channelProtocol", "channel.protocol");
        exactIfAny(clauses, filters, "backendChannelCode", "backendChannelCode", "channel.backendChannelCode");
        exactIfAny(clauses, filters, "finCopyService", "channel.communicationType");

        prefixRegexIfAny(clauses, filters, "owner", "owner", "header.owner");
        exactIfAny(clauses, filters, "workflow", "workflow", "header.workflow");
        exactIfAny(clauses, filters, "workflowModel", "workflowModel", "header.workflowModel");
        exactIfAny(clauses, filters, "originatorApplication", "originatorApplication", "header.originatorApplication");
        exactIfAny(clauses, filters, "sourceSystem", "originatorApplication", "header.originatorApplication");
        exactIfAny(clauses, filters, "processingType", "processingType", "header.processingType");
        exactIfAny(clauses, filters, "processPriority", "processPriority", "header.processPriority");
        exactIfAny(clauses, filters, "profileCode", "profileCode", "header.profileCode");

        exactIfAny(clauses, filters, "ccy", "ampCurrency", "extractedFields.currency");
        booleanIfAny(clauses, filters, "possibleDuplicate", "pdeIndication", "header.pdeIndication");
        booleanIfAny(clauses, filters, "digestMCheckResult", "digestMCheckResult", "protocolParams.digestMCheckResult");
        booleanIfAny(clauses, filters, "digest2CheckResult", "digest2CheckResult", "protocolParams.digest2CheckResult");

        exactIfAny(clauses, filters, "reference", "messageReference", "header.messageReference");
        exactIfAny(clauses, filters, "transactionReference", "transactionReference", "header.transactionReference");
        exactIfAny(clauses, filters, "transferReference", "transferReference");
        exactIfAny(clauses, filters, "relatedReference", "relatedReference");
        addMurFilter(clauses, filters, false);
        exactIfAny(clauses, filters, "uetr",
                "uetr", "extractedFields.uetr", PAYLOAD_ALIAS + ".uetr", PAYLOAD_ALIAS + ".mxExtractedFields.uetr");
        exactIfAny(clauses, filters, "mxInputReference", "mxInputReference");
        exactIfAny(clauses, filters, "mxOutputReference", "mxOutputReference");
        exactIfAny(clauses, filters, "networkReference", "networkReference");
        regexIfAny(clauses, filters, "e2eMessageId", "e2eMessageId");
        regexIfAny(clauses, filters, "amlDetails", "amlDetails");

        exactIfAny(clauses, filters, "logicalTerminalAddress", "finLogicalTerminal", "protocolParams.logicalTerminal");
        exactIfAny(clauses, filters, "applicationId", "finAppId", "protocolParams.appId");
        exactIfAny(clauses, filters, "serviceId", "finServiceId", "protocolParams.serviceId");
        exactIfAny(clauses, filters, "sessionNumber", "finSessionNumber", "protocolParams.sessionNumber");
        exactIfAny(clauses, filters, "finReceiversAddress", "finReceiversAddress", "protocolParams.receiversAddress");

        dateRangeIfAny(clauses, filters, "startDate", "endDate", "dateCreated", "header.dateCreated");
        dateRangeIfAny(clauses, filters, "valueDateFrom", "valueDateTo", "ampValueDate", "extractedFields.valueDate");
        dateRangeIfAny(clauses, filters, "statusDateFrom", "statusDateTo", "statusDate", "status.date");
        dateRangeIfAny(clauses, filters, "receivedDateFrom", "receivedDateTo", "dateReceived", "header.dateReceived");

        numericRangeIf(clauses, filters, "amountFrom", "amountTo", false, "ampAmount", "extractedFields.amount");
        numericRangeIf(clauses, filters, "seqFrom", "seqTo", true, "finSequenceNumber", "protocolParams.sequenceNumber");

        addHistoryFilters(clauses, filters);

        if (clauses.isEmpty()) {
            return new Document();
        }
        return new Document("$and", clauses);
    }

    private Document buildMatch(Map<String, String> filters) {
        List<Document> clauses = new ArrayList<>();

        addMessageFormatFilter(clauses, filters, true);
        messageCodePrefixIfAny(clauses, filters, "messageCode", "messageTypeCode", "header.messageTypeCode");
        exactIfAny(clauses, filters, "io", "direction", "header.direction");
        exactIfAny(clauses, filters, "status", "currentStatus", "status.current");
        exactIfAny(clauses, filters, "phase", "statusPhase", "status.phase");
        exactIfAny(clauses, filters, "action", "statusAction", "status.action");
        exactIfAny(clauses, filters, "reason", "statusReason", "status.reason");
        exactIfAny(clauses, filters, "messagePriority", "finMessagePriority", "protocolParams.messagePriority");

        exactIfAny(clauses, filters, "networkProtocol", "protocol", "header.protocol");
        exactIfAny(clauses, filters, "networkChannel", "header.backendChannel");
        exactIfAny(clauses, filters, "networkPriority", "networkPriority", "header.networkPriority");
        exactIfAny(clauses, filters, "deliveryMode", "protocolParams.deliveryMode", PAYLOAD_ALIAS + ".deliveryMode");
        exactIfAny(clauses, filters, "service", "service", "header.service");
        exactIfAny(clauses, filters, "backendChannelProtocol", "channelProtocol", "channel.protocol");
        exactIfAny(clauses, filters, "backendChannelCode", "backendChannelCode", "channel.backendChannelCode");
        exactIfAny(clauses, filters, "finCopyService", "channel.communicationType");

        prefixRegexIfAny(clauses, filters, "owner", "owner", "header.owner");
        exactIfAny(clauses, filters, "workflow", "workflow", "header.workflow");
        exactIfAny(clauses, filters, "workflowModel", "workflowModel", "header.workflowModel");
        exactIfAny(clauses, filters, "originatorApplication", "originatorApplication", "header.originatorApplication");
        exactIfAny(clauses, filters, "sourceSystem", "originatorApplication", "header.originatorApplication");
        exactIfAny(clauses, filters, "processingType", "processingType", "header.processingType");
        exactIfAny(clauses, filters, "processPriority", "processPriority", "header.processPriority");
        exactIfAny(clauses, filters, "profileCode", "profileCode", "header.profileCode");

        exactIfAny(clauses, filters, "ccy", "ampCurrency", "extractedFields.currency", PAYLOAD_ALIAS + ".currency");

        addBicFilter(clauses, filters, "sender", true);
        addBicFilter(clauses, filters, "receiver", false);
        regexIfAny(clauses, filters, "correspondent", "correspondent", PAYLOAD_ALIAS + ".senderCorrespondent");

        booleanIfAny(clauses, filters, "possibleDuplicate", "pdeIndication", "header.pdeIndication");
        booleanIfAny(clauses, filters, "digestMCheckResult", "digestMCheckResult", "protocolParams.digestMCheckResult");
        booleanIfAny(clauses, filters, "digest2CheckResult", "digest2CheckResult", "protocolParams.digest2CheckResult");

        exactIfAny(clauses, filters, "reference", "messageReference", "header.messageReference");
        exactIfAny(clauses, filters, "transactionReference", "transactionReference", "header.transactionReference");
        exactIfAny(clauses, filters, "transferReference", "transferReference");
        exactIfAny(clauses, filters, "relatedReference", "relatedReference");
        addMurFilter(clauses, filters, true);
        addUetrFilter(clauses, filters);
        exactIfAny(clauses, filters, "mxInputReference", "mxInputReference");
        exactIfAny(clauses, filters, "mxOutputReference", "mxOutputReference");
        exactIfAny(clauses, filters, "networkReference", "networkReference");
        regexIfAny(clauses, filters, "e2eMessageId", "e2eMessageId");
        regexIfAny(clauses, filters, "amlDetails", "amlDetails");

        exactIfAny(clauses, filters, "logicalTerminalAddress",
                "finLogicalTerminal", "protocolParams.logicalTerminal", PAYLOAD_ALIAS + ".mtParsedPayload.block1.logicalTerminalAddress");
        exactIfAny(clauses, filters, "applicationId",
                "finAppId", "protocolParams.appId", PAYLOAD_ALIAS + ".mtParsedPayload.block1.applicationId");
        exactIfAny(clauses, filters, "serviceId",
                "finServiceId", "protocolParams.serviceId", PAYLOAD_ALIAS + ".mtParsedPayload.block1.serviceId");
        exactIfAny(clauses, filters, "sessionNumber",
                "finSessionNumber", "protocolParams.sessionNumber", PAYLOAD_ALIAS + ".mtParsedPayload.block1.sessionNumber");
        exactIfAny(clauses, filters, "finReceiversAddress",
                "finReceiversAddress", "protocolParams.receiversAddress", PAYLOAD_ALIAS + ".mtParsedPayload.block2.receiverAddress");

        dateRangeIfAny(clauses, filters, "startDate", "endDate", "dateCreated", "header.dateCreated");
        dateRangeIfAny(clauses, filters, "valueDateFrom", "valueDateTo", "ampValueDate", "extractedFields.valueDate");
        dateRangeIfAny(clauses, filters, "statusDateFrom", "statusDateTo", "statusDate", "status.date");
        dateRangeIfAny(clauses, filters, "receivedDateFrom", "receivedDateTo", "dateReceived", "header.dateReceived");

        numericRangeIf(clauses, filters, "amountFrom", "amountTo",
                false, "ampAmount", "extractedFields.amount", PAYLOAD_ALIAS + ".amount");
        numericRangeIf(clauses, filters, "seqFrom", "seqTo",
                true, "finSequenceNumber", "protocolParams.sequenceNumber", PAYLOAD_ALIAS + ".mtParsedPayload.block1.sequenceNumber");

        addHistoryFilters(clauses, filters);
        addPayloadFilters(clauses, filters);
        addFreeTextFilter(clauses, filters);

        filters.forEach((param, value) -> {
            if (!HANDLED_PARAMS.contains(param) && notBlank(value)) {
                clauses.add(buildDynamicClause(param, value));
            }
        });

        if (clauses.isEmpty()) {
            return new Document();
        }
        return new Document("$and", clauses);
    }

    private void addHistoryFilters(List<Document> clauses, Map<String, String> filters) {
        String historyEntity = filters.get("historyEntity");
        if (notBlank(historyEntity)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", regexCondition("entity", historyEntity))));
        }

        String historyDescription = filters.get("historyDescription");
        if (notBlank(historyDescription)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", regexCondition("comment", historyDescription))));
        }

        String historyPhase = filters.get("historyPhase");
        if (notBlank(historyPhase)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", new Document("phase", historyPhase))));
        }

        String historyAction = filters.get("historyAction");
        if (notBlank(historyAction)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", new Document("action", historyAction))));
        }

        String historyUser = filters.get("historyUser");
        if (notBlank(historyUser)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", regexCondition("user", historyUser))));
        }

        String historyChannel = filters.get("historyChannel");
        if (notBlank(historyChannel)) {
            clauses.add(new Document("historyLines",
                    new Document("$elemMatch", regexCondition("channel", historyChannel))));
        }
    }

    private void addBicFilter(List<Document> clauses, Map<String, String> filters, String param, boolean sender) {
        String value = filters.get(param);
        if (!notBlank(value)) {
            return;
        }
        List<Document> orClauses = new ArrayList<>();
        String normalized = value.trim();
        String mxField = sender
                ? PAYLOAD_ALIAS + ".applicationHeader.fromBic"
                : PAYLOAD_ALIAS + ".applicationHeader.toBic";
        String mtProtocolField = sender
                ? "protocolParams.requester"
                : "protocolParams.responder";
        String mtLegacyProtocolField = sender
                ? "protocolParams.requestor"
                : "protocolParams.responder";

        orClauses.add(regexClause(mxField, normalized));
        orClauses.add(regexClause(mtProtocolField, normalized));
        if (sender) {
            orClauses.add(regexClause(mtLegacyProtocolField, normalized));
            orClauses.add(regexClause("senderAddress", normalized));
            orClauses.add(regexClause("header.senderAddress", normalized));
            orClauses.add(regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.block1.logicalTerminalAddress", normalized));
        }
        clauses.add(new Document("$or", orClauses));
    }

    private void addMurFilter(List<Document> clauses, Map<String, String> filters, boolean includePayloadLookup) {
        String value = filters.get("mur");
        if (!notBlank(value)) {
            return;
        }
        String normalized = value.trim();
        List<Document> orClauses = new ArrayList<>();
        orClauses.add(new Document("protocolParams.userReference", normalized));

        if (includePayloadLookup) {
            String block3Pattern = "\\{108:" + escapeRegex(normalized) + "\\}";
            orClauses.add(new Document(PAYLOAD_ALIAS + ".mtParsedPayload.rawBlock3",
                    new Document("$regex", block3Pattern).append("$options", "i")));
            orClauses.add(new Document(PAYLOAD_ALIAS + ".rawFin",
                    new Document("$regex", block3Pattern).append("$options", "i")));
            orClauses.add(new Document(PAYLOAD_ALIAS + ".mtParsedPayload.rawFields._raw",
                    new Document("$regex", block3Pattern).append("$options", "i")));
            orClauses.add(new Document("messageReference", normalized));
            orClauses.add(new Document("header.messageReference", normalized));
        }

        clauses.add(new Document("$or", orClauses));
    }

    private void addPayloadFilters(List<Document> clauses, Map<String, String> filters) {
        String tag = filters.get("block4Tag");
        String value = filters.get("block4Value");

        if (notBlank(tag)) {
            clauses.add(regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.rawBlock4", ":" + tag + ":"));
        }

        if (notBlank(value)) {
            clauses.add(new Document("$or", Arrays.asList(
                    new Document("mtPayload.block4Fields",
                            new Document("$elemMatch", regexCondition("rawValue", value))),
                    regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.rawBlock4", value),
                    regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.rawFields._raw", value)
            )));
        }
    }

    private void addFreeTextFilter(List<Document> clauses, Map<String, String> filters) {
        String freeText = filters.get("freeSearchText");
        if (!notBlank(freeText)) {
            return;
        }

        List<Document> orClauses = new ArrayList<>(Arrays.asList(
                regexClause("messageReference", freeText),
                regexClause("header.messageReference", freeText),
                regexClause("transactionReference", freeText),
                regexClause("header.transactionReference", freeText),
                regexClause("protocolParams.userReference", freeText),
                regexClause("senderAddress", freeText),
                regexClause("header.senderAddress", freeText),
                regexClause("receiverAddress", freeText),
                regexClause("header.receiverAddress", freeText),
                regexClause("senderName", freeText),
                regexClause("header.senderName", freeText),
                regexClause("receiverName", freeText),
                regexClause("header.receiverName", freeText),
                regexClause("owner", freeText),
                regexClause("header.owner", freeText),
                regexClause("workflow", freeText),
                regexClause("header.workflow", freeText),
                regexClause("currentStatus", freeText),
                regexClause("status.current", freeText),
                regexClause("statusMessage", freeText),
                regexClause("status.message", freeText),
                regexClause("status.phase", freeText),
                regexClause("status.action", freeText),
                regexClause("status.reason", freeText),
                regexClause("body.rawPayload", freeText),
                regexClause(PAYLOAD_ALIAS + ".mxRawPayload", freeText),
                regexClause(PAYLOAD_ALIAS + ".mtRawPayload", freeText),
                regexClause(PAYLOAD_ALIAS + ".mxExtractedFields", freeText),
                regexClause(PAYLOAD_ALIAS + ".applicationHeader.businessMessageId", freeText),
                regexClause(PAYLOAD_ALIAS + ".applicationHeader.messageDefinitionId", freeText),
                regexClause(PAYLOAD_ALIAS + ".applicationHeader.businessService", freeText),
                regexClause("channel.backendChannelCode", freeText),
                regexClause("channel.backendChannelName", freeText),
                regexClause("header.backendChannel", freeText),
                regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.transactionReference", freeText),
                regexClause(PAYLOAD_ALIAS + ".mtParsedPayload.rawBlock4", freeText),
                regexClause(PAYLOAD_ALIAS + ".orderingCustomer", freeText),
                regexClause(PAYLOAD_ALIAS + ".beneficiaryCustomer", freeText),
                regexClause(PAYLOAD_ALIAS + ".remittanceInfo", freeText)
        ));

        orClauses.add(new Document("historyLines",
                new Document("$elemMatch",
                        new Document("$or", Arrays.asList(
                                regexCondition("comment", freeText),
                                regexCondition("entity", freeText)
                        )))));

        clauses.add(new Document("$or", orClauses));
    }

    private void addMessageFormatFilter(List<Document> clauses, Map<String, String> filters, boolean includePayloadAlias) {
        String value = filters.get("messageType");
        if (!notBlank(value)) {
            return;
        }
        String normalized = value.trim();
        if ("MT".equalsIgnoreCase(normalized) || "MX".equalsIgnoreCase(normalized)) {
            clauses.add(new Document("messageFamily", normalized.toUpperCase()));
            return;
        }

        List<Document> orClauses = new ArrayList<>(Arrays.asList(
                new Document("header.messageFormat", value),
                new Document("messageFormat", value)
        ));
        orClauses.add(new Document((includePayloadAlias ? PAYLOAD_ALIAS : "payloadDoc") + ".messageFormat", value));
        clauses.add(new Document("$or", orClauses));
    }

    private void addUetrFilter(List<Document> clauses, Map<String, String> filters) {
        String value = filters.get("uetr");
        if (!notBlank(value)) {
            return;
        }
        String normalized = value.trim();
        String rawBlock3Pattern = "\\{121:" + escapeRegex(normalized) + "\\}";
        clauses.add(new Document("$or", Arrays.asList(
                new Document("uetr", normalized),
                new Document("extractedFields.uetr", normalized),
                new Document(PAYLOAD_ALIAS + ".uetr", normalized),
                new Document(PAYLOAD_ALIAS + ".mxExtractedFields.uetr", normalized),
                new Document(PAYLOAD_ALIAS + ".mtParsedPayload.rawBlock3",
                        new Document("$regex", rawBlock3Pattern).append("$options", "i")),
                new Document(PAYLOAD_ALIAS + ".rawFin",
                        new Document("$regex", rawBlock3Pattern).append("$options", "i")),
                new Document(PAYLOAD_ALIAS + ".mtParsedPayload.rawFields._raw",
                        new Document("$regex", rawBlock3Pattern).append("$options", "i")),
                regexClause("body.rawPayload", normalized),
                regexClause(PAYLOAD_ALIAS + ".mxRawPayload", normalized),
                regexClause(PAYLOAD_ALIAS + ".mtRawPayload", normalized)
        )));
    }

    private void exactIfAny(List<Document> clauses, Map<String, String> filters, String paramKey, String... fieldPaths) {
        String value = filters.get(paramKey);
        if (!notBlank(value)) {
            return;
        }
        clauses.add(fieldPaths.length == 1
                ? new Document(fieldPaths[0], value)
                : new Document("$or", Arrays.stream(fieldPaths).map(path -> new Document(path, value)).collect(Collectors.toList())));
    }

    private void regexIfAny(List<Document> clauses, Map<String, String> filters, String paramKey, String... fieldPaths) {
        String value = filters.get(paramKey);
        if (!notBlank(value)) {
            return;
        }
        clauses.add(fieldPaths.length == 1
                ? regexClause(fieldPaths[0], value)
                : new Document("$or", Arrays.stream(fieldPaths).map(path -> regexClause(path, value)).collect(Collectors.toList())));
    }

    private void prefixRegexIfAny(List<Document> clauses, Map<String, String> filters, String paramKey, String... fieldPaths) {
        String value = filters.get(paramKey);
        if (!notBlank(value)) {
            return;
        }
        clauses.add(fieldPaths.length == 1
                ? prefixRegexClause(fieldPaths[0], value)
                : new Document("$or", Arrays.stream(fieldPaths).map(path -> prefixRegexClause(path, value)).collect(Collectors.toList())));
    }

    private void messageCodePrefixIfAny(List<Document> clauses, Map<String, String> filters, String paramKey, String... fieldPaths) {
        String value = filters.get(paramKey);
        if (!notBlank(value)) {
            return;
        }
        List<String> patterns = buildMessageCodeSearchPatterns(value);
        if (patterns.isEmpty()) {
            return;
        }

        List<Document> fieldClauses = new ArrayList<>();
        for (String fieldPath : fieldPaths) {
            for (String pattern : patterns) {
                fieldClauses.add(new Document(fieldPath, new Document("$regex", pattern).append("$options", "i")));
            }
        }
        clauses.add(new Document("$or", fieldClauses));
    }

    private void booleanIfAny(List<Document> clauses, Map<String, String> filters, String paramKey, String... fieldPaths) {
        String value = filters.get(paramKey);
        if (!notBlank(value)) {
            return;
        }

        boolean boolValue = Boolean.parseBoolean(value);
        List<Document> orClauses = new ArrayList<>();
        for (String fieldPath : fieldPaths) {
            orClauses.add(new Document(fieldPath, boolValue));
            orClauses.add(new Document(fieldPath, String.valueOf(boolValue)));
            orClauses.add(new Document(fieldPath, value));
        }
        clauses.add(new Document("$or", orClauses));
    }

    private void dateRangeIfAny(List<Document> clauses, Map<String, String> filters,
                                String fromKey, String toKey, String... fieldPaths) {
        String from = filters.get(fromKey);
        String to = filters.get(toKey);
        if (!notBlank(from) && !notBlank(to)) {
            return;
        }

        List<Document> pathClauses = new ArrayList<>();
        for (String fieldPath : fieldPaths) {
            Document range = new Document();
            if (notBlank(from)) {
                range.append("$gte", from);
            }
            if (notBlank(to)) {
                range.append("$lte", to + "T23:59:59Z");
            }
            pathClauses.add(new Document(fieldPath, range));
        }

        clauses.add(pathClauses.size() == 1 ? pathClauses.get(0) : new Document("$or", pathClauses));
    }

    private void numericRangeIf(List<Document> clauses, Map<String, String> filters,
                                String fromKey, String toKey, boolean integer, String... fieldPaths) {
        String from = filters.get(fromKey);
        String to = filters.get(toKey);
        if (!notBlank(from) && !notBlank(to)) {
            return;
        }

        try {
            List<Document> exprClauses = new ArrayList<>();
            Object fieldExpr = integer ? integerExpr(fieldPaths) : decimalExpr(fieldPaths);
            if (notBlank(from)) {
                exprClauses.add(new Document("$gte",
                        Arrays.asList(fieldExpr, integer ? Integer.parseInt(from.trim()) : Double.parseDouble(from.trim()))));
            }
            if (notBlank(to)) {
                exprClauses.add(new Document("$lte",
                        Arrays.asList(fieldExpr, integer ? Integer.parseInt(to.trim()) : Double.parseDouble(to.trim()))));
            }
            Object expr = exprClauses.size() == 1 ? exprClauses.get(0) : new Document("$and", exprClauses);
            clauses.add(new Document("$expr", expr));
        } catch (NumberFormatException ignored) {
        }
    }

    private Document buildDynamicClause(String param, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            boolean boolValue = Boolean.parseBoolean(value);
            return new Document("$or", Arrays.asList(
                    new Document(param, boolValue),
                    new Document(param, String.valueOf(boolValue)),
                    new Document(param, value)
            ));
        }
        return regexClause(param, value);
    }

    private SearchResponse toResponse(Document doc) {
        SearchResponse response = new SearchResponse();

        Document header = documentAt(doc, "header");
        Document status = documentAt(doc, "status");
        Document protocolParams = documentAt(doc, "protocolParams");
        Document channel = documentAt(doc, "channel");
        Document extractedFields = documentAt(doc, "extractedFields");
        Document bulkInfo = documentAt(doc, "bulkInfo");
        Document body = documentAt(doc, "body");
        Document payloadDoc = documentAt(doc, PAYLOAD_ALIAS);
        Document mtParsedPayload = documentAt(payloadDoc, "mtParsedPayload");
        Document block1 = documentAt(mtParsedPayload, "block1");
        Document block2 = documentAt(mtParsedPayload, "block2");
        String messageType = firstNonBlank(doc.getString("messageFamily"), payloadDoc.getString("messageFamily"));
        String messageCode = firstNonBlank(doc.getString("messageTypeCode"), header.getString("messageTypeCode"), payloadDoc.getString("messageTypeCode"));

        String rawFin = firstNonBlank(
                stringValue(mtParsedPayloadAt(mtParsedPayload, "rawFields", "_raw")),
                firstNonBlank(payloadDoc.getString("rawFin"), mtParsedPayload.getString("rawFin")),
                composeRawFin(mtParsedPayload),
                body.getString("rawPayload")
        );
        List<Map<String, Object>> block4Fields = buildBlock4Fields(payloadDoc, mtParsedPayload, rawFin, messageType, messageCode);
        String mxRawPayload = firstNonBlank(
                payloadDoc.getString("mxRawPayload"),
                doc.getString("mxRawPayload"),
                body.getString("rawPayload")
        );
        Document mxExtractedFields = documentAt(payloadDoc, "mxExtractedFields");
        if (mxExtractedFields.isEmpty()) {
            mxExtractedFields = documentAt(doc, "mxExtractedFields");
        }
        List<Map<String, Object>> mxExtendedFields = buildMxExtendedFields(mxExtractedFields, mxRawPayload, messageType, messageCode);
        Map<String, String> mxNodeLabels = buildMxNodeLabels(mxExtendedFields);
        String rawBlock1 = firstNonBlank(mtParsedPayload.getString("rawBlock1"), extractBlock1Content(rawFin));
        String rawBlock2 = firstNonBlank(mtParsedPayload.getString("rawBlock2"), extractBlock2Content(rawFin));
        String senderBic = resolveSenderBic(doc, protocolParams, payloadDoc);
        String receiverBic = resolveReceiverBic(doc, protocolParams, payloadDoc);

        response.setId(stringValue(doc.get("_id")));
        parseIntStr(firstNonBlank(
                doc.getString("finSequenceNumber"),
                protocolParams.getString("sequenceNumber"),
                block1.getString("sequenceNumber"),
                parseSequenceNumberFromBlock1(rawBlock1)
        ), response::setSequenceNumber);
        response.setSessionNumber(firstNonBlank(
                doc.getString("finSessionNumber"),
                protocolParams.getString("sessionNumber"),
                block1.getString("sessionNumber"),
                parseSessionNumberFromBlock1(rawBlock1)
        ));

        response.setMessageType(messageType);
        response.setMessageCode(messageCode);
        response.setMessageFormat(firstNonBlank(doc.getString("messageFormat"), header.getString("messageFormat"), payloadDoc.getString("messageFormat")));
        response.setMessageTypeDescription(firstNonBlank(doc.getString("messageTypeDescription"), header.getString("messageTypeDescription")));

        response.setStatus(firstNonBlank(doc.getString("currentStatus"), status.getString("current")));
        response.setPhase(firstNonBlank(doc.getString("statusPhase"), status.getString("phase")));
        response.setAction(firstNonBlank(doc.getString("statusAction"), status.getString("action")));
        response.setReason(firstNonBlank(doc.getString("statusReason"), status.getString("reason")));
        response.setStatusMessage(firstNonBlank(doc.getString("statusMessage"), status.getString("message")));
        response.setStatusChangeSource(firstNonBlank(doc.getString("statusChangeSource"), status.getString("changeSource")));
        response.setStatusDecision(firstNonBlank(doc.getString("statusDecision"), status.getString("decision")));
        response.setIo(firstNonBlank(doc.getString("direction"), header.getString("direction")));

        response.setCreationDate(firstNonBlank(doc.getString("dateCreated"), header.getString("dateCreated")));
        response.setReceivedDT(firstNonBlank(doc.getString("dateReceived"), header.getString("dateReceived")));
        response.setStatusDate(firstNonBlank(doc.getString("statusDate"), status.getString("date")));
        response.setValueDate(firstNonBlank(doc.getString("ampValueDate"), extractedFields.getString("valueDate")));

        response.setSender(senderBic);
        response.setReceiver(receiverBic);
        response.setSenderInstitutionName(firstNonBlank(doc.getString("senderName"), header.getString("senderName")));
        response.setReceiverInstitutionName(firstNonBlank(doc.getString("receiverName"), header.getString("receiverName")));

        response.setReference(firstNonBlank(doc.getString("messageReference"), header.getString("messageReference")));
        response.setTransactionReference(firstNonBlank(doc.getString("transactionReference"), header.getString("transactionReference")));

        response.setAmount(parseFlexibleDouble(firstNonBlank(doc.getString("ampAmount"), extractedFields.getString("amount"), payloadDoc.getString("amount"))));
        response.setCcy(firstNonBlank(doc.getString("ampCurrency"), extractedFields.getString("currency"), payloadDoc.getString("currency")));
        response.setDetailsOfCharges(firstNonBlank(doc.getString("ampDetailsOfCharges"), extractedFields.getString("detailsOfCharges"), payloadDoc.getString("detailsOfCharges"), mtParsedPayload.getString("detailsOfCharges")));
        response.setRemittanceInfo(firstNonBlank(doc.getString("ampRemittanceInformation"), extractedFields.getString("remittanceInformation"), payloadDoc.getString("remittanceInfo"), mtParsedPayload.getString("remittanceInfo")));

        response.setNetworkProtocol(firstNonBlank(doc.getString("protocol"), header.getString("protocol"), payloadDoc.getString("protocol")));
        response.setNetworkChannel(firstNonBlank(doc.getString("networkChannel"), header.getString("networkChannel"), header.getString("backendChannel")));
        response.setNetworkPriority(firstNonBlank(doc.getString("networkPriority"), header.getString("networkPriority")));
        response.setDeliveryMode(firstNonBlank(protocolParams.getString("deliveryMode"), payloadDoc.getString("deliveryMode"),
                doc.getString("communicationType"), channel.getString("communicationType")));
        response.setCommunicationType(response.getDeliveryMode());
        response.setService(firstNonBlank(doc.getString("service"), header.getString("service")));
        response.setBackendChannel(firstNonBlank(doc.getString("backendChannel"), header.getString("backendChannel"), channel.getString("backendChannelName")));
        response.setBackendChannelCode(firstNonBlank(doc.getString("backendChannelCode"), channel.getString("backendChannelCode")));
        response.setBackendChannelDescription(firstNonBlank(doc.getString("backendChannelDescription"), channel.getString("backendChannelDescription")));
        response.setChannelCode(firstNonBlank(doc.getString("channelCode"), channel.getString("code")));
        response.setBackendChannelProtocol(firstNonBlank(doc.getString("channelProtocol"), channel.getString("protocol")));

        response.setOwner(firstNonBlank(doc.getString("owner"), header.getString("owner")));
        response.setWorkflow(firstNonBlank(doc.getString("workflow"), header.getString("workflow")));
        response.setWorkflowModel(firstNonBlank(doc.getString("workflowModel"), header.getString("workflowModel")));
        response.setProcessingType(firstNonBlank(doc.getString("processingType"), header.getString("processingType")));
        response.setProcessPriority(firstNonBlank(doc.getString("processPriority"), header.getString("processPriority")));
        response.setProfileCode(firstNonBlank(doc.getString("profileCode"), header.getString("profileCode")));
        response.setOriginatorApplication(firstNonBlank(doc.getString("originatorApplication"), header.getString("originatorApplication")));

        response.setApplicationId(firstNonBlank(doc.getString("finAppId"), protocolParams.getString("appId"), block1.getString("applicationId")));
        response.setServiceId(firstNonBlank(doc.getString("finServiceId"), protocolParams.getString("serviceId"), block1.getString("serviceId")));
        response.setLogicalTerminalAddress(firstNonBlank(doc.getString("finLogicalTerminal"), protocolParams.getString("logicalTerminal"), block1.getString("logicalTerminalAddress")));
        response.setMessagePriority(firstNonBlank(doc.getString("finMessagePriority"), protocolParams.getString("messagePriority"), block2.getString("messagePriority")));
        response.setFinDirectionId(firstNonBlank(doc.getString("finDirectionId"), protocolParams.getString("directionId"), block2.getString("directionId")));
        response.setFinMessageType(firstNonBlank(doc.getString("finMessageType"), protocolParams.getString("messageType"), block2.getString("messageType")));
        response.setFinReceiversAddress(firstNonBlank(doc.getString("finReceiversAddress"), protocolParams.getString("receiversAddress"), block2.getString("receiverAddress")));

        response.setDigestMCheckResult(firstNonBlank(doc.getString("digestMCheckResult"), stringValue(protocolParams.get("digestMCheckResult"))));
        response.setDigest2CheckResult(firstNonBlank(doc.getString("digest2CheckResult"), stringValue(protocolParams.get("digest2CheckResult"))));

        response.setBulkType(firstNonBlank(doc.getString("bulkType"), bulkInfo.getString("bulkType")));
        parseIntObj(firstNonBlankObject(doc.get("bulkSequenceNumber"), bulkInfo.get("sequenceNumber")), response::setBulkSequenceNumber);
        parseIntObj(firstNonBlankObject(doc.get("bulkTotalMessages"), bulkInfo.get("totalMessages")), response::setBulkTotalMessages);

        response.setPdeIndication(firstNonBlank(doc.getString("pdeIndication"), stringValue(header.get("pdeIndication"))));
        response.setPossibleDuplicate("true".equalsIgnoreCase(response.getPdeIndication()));

        String derivedMur = deriveMur(doc, messageType, messageCode, protocolParams, payloadDoc, mtParsedPayload, rawFin);
        response.setUserReference(derivedMur);
        response.setMur(derivedMur);
        response.setUetr(deriveUetr(messageType, messageCode, doc, extractedFields, payloadDoc, mtParsedPayload, rawFin));
        response.setFinCopyService(firstNonBlank(
                channel.getString("communicationType"),
                channel.getString("backendChannelCode"),
                channel.getString("backendChannelName"),
                header.getString("service")
        ));
        response.setBankOperationCode(firstNonBlank(documentAt(doc, "mtPayload").getString("bankOperationCode"), payloadDoc.getString("bankOperationCode"), mtParsedPayload.getString("bankOperationCode")));
        response.setPayloadCurrency(firstNonBlank(documentAt(doc, "mtPayload").getString("currency"), payloadDoc.getString("currency"), mtParsedPayload.getString("currency")));
        response.setPayloadValueDate(firstNonBlank(documentAt(doc, "mtPayload").getString("valueDate"), payloadDoc.getString("valueDate"), mtParsedPayload.getString("valueDate")));
        response.setInterbankSettledAmount(firstNonBlank(documentAt(doc, "mtPayload").getString("interbankSettledAmount"), mtParsedPayload.getString("interbankSettledAmount")));
        response.setInstructedCurrency(firstNonBlank(documentAt(doc, "mtPayload").getString("instructedCurrency"), payloadDoc.getString("instructedCurrency"), mtParsedPayload.getString("instructedCurrency")));
        response.setInstructedAmount(firstNonBlank(documentAt(doc, "mtPayload").getString("instructedAmount"), payloadDoc.getString("instructedAmount"), mtParsedPayload.getString("instructedAmount")));

        response.setOrderingCustomer(firstNonBlank(documentAt(doc, "mtPayload").getString("orderingCustomer"), payloadDoc.getString("orderingCustomer"), mtParsedPayload.getString("orderingCustomer")));
        response.setOrderingInstitution(firstNonBlank(documentAt(doc, "mtPayload").getString("orderingInstitution"), payloadDoc.getString("orderingInstitution"), mtParsedPayload.getString("orderingInstitution")));
        response.setSenderCorrespondent(firstNonBlank(documentAt(doc, "mtPayload").getString("senderCorrespondent"), payloadDoc.getString("senderCorrespondent"), mtParsedPayload.getString("senderCorrespondent")));
        response.setAccountWithInstitution(firstNonBlank(documentAt(doc, "mtPayload").getString("accountWithInstitution"), payloadDoc.getString("accountWithInstitution"), mtParsedPayload.getString("accountWithInstitution")));
        response.setBeneficiaryCustomer(firstNonBlank(documentAt(doc, "mtPayload").getString("beneficiaryCustomer"), payloadDoc.getString("beneficiaryCustomer"), mtParsedPayload.getString("beneficiaryCustomer")));

        response.setCorrespondent(firstNonBlank(response.getSenderCorrespondent(), doc.getString("correspondent")));

        response.setPayloadFieldCount(computePayloadFieldCount(mtParsedPayload, block4Fields));
        response.setPayloadSize(firstNonBlank(documentAt(doc, "mtPayload").getString("payloadSize"), payloadDoc.getString("payloadSize")));
        response.setRawFin(rawFin);
        response.setFinHeaderFields(buildFinHeaderFields(messageType, messageCode, protocolParams, block1, block2, rawFin));
        response.setBlock4Fields(block4Fields);
        response.setMxExtendedFields(mxExtendedFields);
        response.setMxNodeLabels(mxNodeLabels);
        response.setHistoryLines(toMapList(doc.get("historyLines")));

        Document rawMessage = new Document(doc);
        rawMessage.remove(PAYLOAD_ALIAS);
        if (!payloadDoc.isEmpty()) {
            rawMessage.put("mtPayload", buildCompatibilityPayload(payloadDoc, mtParsedPayload, block4Fields, rawFin, mxExtendedFields, mxRawPayload, mxNodeLabels));
        }
        response.setRawMessage(new LinkedHashMap<>(rawMessage));

        response.setFormat(response.getMessageType());
        response.setType(response.getMessageCode());
        response.setDate(dateOnly(response.getCreationDate()));
        response.setTime(timeOnly(response.getCreationDate()));
        response.setDirection(response.getIo());
        response.setNetwork(response.getNetworkProtocol());
        response.setOwnerUnit(response.getOwner());
        response.setCurrency(response.getCcy());
        response.setFinCopy(response.getFinCopyService());
        response.setSourceSystem(response.getOriginatorApplication());

        return response;
    }

    private SearchResponse toSearchRowResponse(Document doc) {
        SearchResponse response = toResponse(doc);
        response.setRawFin(null);
        response.setFinHeaderFields(Collections.emptyList());
        response.setBlock4Fields(Collections.emptyList());
        response.setMxExtendedFields(Collections.emptyList());
        response.setMxNodeLabels(Collections.emptyMap());
        response.setHistoryLines(Collections.emptyList());
        response.setRawMessage(null);
        return response;
    }

    private Document buildCompatibilityPayload(Document payloadDoc, Document mtParsedPayload,
                                               List<Map<String, Object>> block4Fields, String rawFin,
                                               List<Map<String, Object>> mxExtendedFields, String mxRawPayload,
                                               Map<String, String> mxNodeLabels) {
        Document compatibility = new Document();
        if (!mtParsedPayload.isEmpty()) {
            compatibility.putAll(mtParsedPayload);
        }
        compatibility.put("block1", new Document(documentAt(mtParsedPayload, "block1")));
        compatibility.put("block2", new Document(documentAt(mtParsedPayload, "block2")));
        compatibility.put("block4Fields", block4Fields);
        compatibility.put("rawFin", rawFin);
        compatibility.put("fieldCount", computePayloadFieldCount(mtParsedPayload, block4Fields));
        compatibility.put("payloadSize", payloadDoc.getString("payloadSize"));
        compatibility.put("payloadEncoding", payloadDoc.getString("payloadEncoding"));
        compatibility.put("textPayload", payloadDoc.getString("textPayload"));
        compatibility.put("digest", payloadDoc.getString("digest"));
        compatibility.put("digestAlgorithm", payloadDoc.getString("digestAlgorithm"));
        if (notBlank(mxRawPayload)) {
            compatibility.put("mxRawPayload", mxRawPayload);
        }
        if (mxExtendedFields != null && !mxExtendedFields.isEmpty()) {
            compatibility.put("mxExtendedFields", mxExtendedFields);
        }
        if (mxNodeLabels != null && !mxNodeLabels.isEmpty()) {
            compatibility.put("mxNodeLabels", mxNodeLabels);
        }
        return compatibility;
    }

    private List<Map<String, Object>> buildBlock4Fields(Document payloadDoc, Document mtParsedPayload, String rawFin,
                                                        String messageType, String messageCode) {
        List<Map<String, Object>> existingRows = existingBlock4Fields(payloadDoc, mtParsedPayload);
        if (!existingRows.isEmpty()) {
            return enrichMtBlock4Labels(messageType, messageCode, existingRows);
        }

        Document rawFields = documentAt(mtParsedPayload, "rawFields");
        if (!rawFields.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, Object> entry : rawFields.entrySet()) {
                String tag = entry.getKey();
                if ("_raw".equals(tag)) {
                    continue;
                }
                List<String> values = toStringList(entry.getValue());
                if (values.isEmpty()) {
                    rows.add(block4Row(tag, null, null));
                    continue;
                }
                for (String value : values) {
                    rows.add(block4Row(tag, null, value));
                }
            }
            return enrichMtBlock4Labels(messageType, messageCode, rows);
        }

        List<Map<String, Object>> rawFinRows = buildBlock4FieldsFromRawFin(rawFin);
        if (!rawFinRows.isEmpty()) {
            return enrichMtBlock4Labels(messageType, messageCode, rawFinRows);
        }

        String textPayload = firstNonBlank(payloadDoc.getString("textPayload"), mtParsedPayload.getString("textPayload"));
        return enrichMtBlock4Labels(messageType, messageCode, buildBlock4FieldsFromTextPayload(textPayload));
    }

    private List<Map<String, Object>> buildMxExtendedFields(Document mxExtractedFields, String mxRawPayload,
                                                            String messageType, String messageCode) {
        if (!isMxMessage(messageType, messageCode)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = buildMxExtendedFieldsFromExtracted(mxExtractedFields);
        if (!rows.isEmpty()) {
            return enrichMxLabels(rows);
        }

        if (notBlank(mxRawPayload)) {
            return enrichMxLabels(buildMxExtendedFieldsFromRawXml(mxRawPayload));
        }

        return Collections.emptyList();
    }

    private List<Map<String, Object>> buildMxExtendedFieldsFromExtracted(Document mxExtractedFields) {
        if (mxExtractedFields == null || mxExtractedFields.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Object allFieldValues = mxExtractedFields.get("allFieldValues");
        if (allFieldValues instanceof Map<?, ?> valuesMap) {
            for (Map.Entry<?, ?> entry : valuesMap.entrySet()) {
                String path = stringValue(entry.getKey());
                if (!notBlank(path)) {
                    continue;
                }
                List<String> values = toStringList(entry.getValue());
                if (values.isEmpty()) {
                    rows.add(mxExtendedRow(mxTagFromPath(path), null, null, path));
                    continue;
                }
                for (String value : values) {
                    rows.add(mxExtendedRow(mxTagFromPath(path), null, value, path));
                }
            }
        }
        if (!rows.isEmpty()) {
            return rows;
        }

        Object allFields = mxExtractedFields.get("allFields");
        if (allFields instanceof Map<?, ?> fieldsMap) {
            for (Map.Entry<?, ?> entry : fieldsMap.entrySet()) {
                String path = stringValue(entry.getKey());
                if (!notBlank(path)) {
                    continue;
                }
                rows.add(mxExtendedRow(mxTagFromPath(path), null, stringValue(entry.getValue()), path));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> buildMxExtendedFieldsFromRawXml(String mxRawPayload) {
        if (!notBlank(mxRawPayload)) {
            return Collections.emptyList();
        }
        String normalizedXml = normalizePotentialXml(mxRawPayload);
        if (!looksLikeXml(normalizedXml)) {
            return Collections.emptyList();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {
            }

            org.w3c.dom.Document xml = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(normalizedXml)));
            List<Map<String, Object>> rows = new ArrayList<>();
            collectMxXmlRows(xml.getDocumentElement(), stripNamespace(xml.getDocumentElement().getNodeName()), rows);
            return rows;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void collectMxXmlRows(org.w3c.dom.Node node, String currentPath, List<Map<String, Object>> rows) {
        if (node == null) {
            return;
        }

        if (node.hasAttributes()) {
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                org.w3c.dom.Node attribute = node.getAttributes().item(i);
                String attrName = stripNamespace(attribute.getNodeName());
                String attributePath = currentPath + "/@" + attrName;
                rows.add(mxExtendedRow(attrName, null, attribute.getNodeValue(), attributePath));
            }
        }

        List<org.w3c.dom.Node> elementChildren = new ArrayList<>();
        StringBuilder directText = new StringBuilder();
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node child = node.getChildNodes().item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                elementChildren.add(child);
            } else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                String text = child.getTextContent();
                if (notBlank(text)) {
                    if (directText.length() > 0) {
                        directText.append(' ');
                    }
                    directText.append(text.trim());
                }
            }
        }

        if (directText.length() > 0) {
            rows.add(mxExtendedRow(mxTagFromPath(currentPath), null, directText.toString(), currentPath));
        }

        Map<String, Integer> siblingCounts = new HashMap<>();
        for (org.w3c.dom.Node child : elementChildren) {
            String childName = stripNamespace(child.getNodeName());
            int childIndex = siblingCounts.merge(childName, 1, Integer::sum);
            String childPath = currentPath + "/" + childName + (childIndex > 1 ? "[" + childIndex + "]" : "");
            collectMxXmlRows(child, childPath, rows);
        }
    }

    private Map<String, Object> mxExtendedRow(String tag, String label, String value, String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", tag);
        row.put("label", label);
        row.put("rawValue", value == null || value.isBlank() ? "—" : value);
        row.put("path", path);
        row.put("components", Collections.emptyMap());
        return row;
    }

    private String normalizePotentialXml(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("\uFEFF", "").stripLeading();
        int firstAngleBracket = normalized.indexOf('<');
        if (firstAngleBracket > 0) {
            String prefix = normalized.substring(0, firstAngleBracket).trim();
            if (prefix.isEmpty()) {
                normalized = normalized.substring(firstAngleBracket);
            }
        }
        return normalized;
    }

    private boolean looksLikeXml(String value) {
        if (!notBlank(value)) {
            return false;
        }
        return value.charAt(0) == '<';
    }

    private List<Map<String, Object>> buildBlock4FieldsFromRawFin(String rawFin) {
        String block4 = extractBlock4Content(rawFin);
        if (!notBlank(block4)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        String currentTag = null;
        StringBuilder currentValue = new StringBuilder();
        String normalized = block4.replace("\r\n", "\n").replace('\r', '\n');

        for (String line : normalized.split("\n")) {
            if (!notBlank(line)) {
                if (currentTag != null) {
                    currentValue.append('\n');
                }
                continue;
            }

            if ("-}".equals(line.trim())) {
                continue;
            }

            if (line.startsWith(":")) {
                int secondColon = line.indexOf(':', 1);
                if (secondColon > 1) {
                    if (currentTag != null) {
                        rows.add(block4Row(currentTag, null, cleanBlock4Value(currentValue.toString())));
                    }
                    currentTag = line.substring(1, secondColon).trim();
                    currentValue.setLength(0);
                    currentValue.append(line.substring(secondColon + 1));
                    continue;
                }
            }

            if (currentTag != null) {
                if (currentValue.length() > 0) {
                    currentValue.append('\n');
                }
                currentValue.append(line);
            }
        }

        if (currentTag != null) {
            rows.add(block4Row(currentTag, null, cleanBlock4Value(currentValue.toString())));
        }

        return rows;
    }

    private List<Map<String, Object>> buildBlock4FieldsFromTextPayload(String textPayload) {
        if (!notBlank(textPayload)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        String currentTag = null;
        String currentLabel = null;
        String pendingLabel = null;
        StringBuilder currentValue = new StringBuilder();
        String normalized = textPayload.replace("\r\n", "\n").replace('\r', '\n');

        for (String rawLine : normalized.split("\n")) {
            String line = rawLine == null ? "" : rawLine;
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (currentTag != null && currentValue.length() > 0) {
                    currentValue.append('\n');
                }
                continue;
            }

            Matcher matcher = TEXT_PAYLOAD_TAG_LINE_PATTERN.matcher(line);
            if (matcher.matches()) {
                if (currentTag != null) {
                    rows.add(block4Row(currentTag, currentLabel, cleanBlock4Value(currentValue.toString())));
                }
                currentTag = matcher.group(2).trim();
                currentLabel = resolveTextPayloadLabel(matcher.group(1), pendingLabel);
                pendingLabel = null;
                currentValue.setLength(0);
                String initialValue = cleanBlock4Value(matcher.group(3));
                if (notBlank(initialValue)) {
                    currentValue.append(initialValue);
                }
                continue;
            }

            if (currentTag != null) {
                if (currentValue.length() > 0) {
                    currentValue.append('\n');
                }
                currentValue.append(line.stripTrailing());
            } else {
                pendingLabel = trimmed;
            }
        }

        if (currentTag != null) {
            rows.add(block4Row(currentTag, currentLabel, cleanBlock4Value(currentValue.toString())));
        }

        return rows;
    }

    private Map<String, Object> block4Row(String tag, String label, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", tag);
        row.put("label", label);
        row.put("rawValue", value == null || value.isBlank() ? "—" : value);
        row.put("components", Collections.emptyMap());
        return row;
    }

    private String resolveTextPayloadLabel(String inlineLabel, String pendingLabel) {
        String cleanedInlineLabel = cleanTextPayloadLabel(inlineLabel);
        String cleanedPendingLabel = cleanTextPayloadLabel(pendingLabel);
        if (notBlank(cleanedInlineLabel) && !GENERIC_TEXT_PAYLOAD_LABEL_PATTERN.matcher(cleanedInlineLabel).matches()) {
            return cleanedInlineLabel;
        }
        if (notBlank(cleanedPendingLabel)) {
            return cleanedPendingLabel;
        }
        return notBlank(cleanedInlineLabel) ? cleanedInlineLabel : null;
    }

    private String cleanTextPayloadLabel(String label) {
        if (!notBlank(label)) {
            return null;
        }
        String cleaned = label.strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private List<Map<String, Object>> existingBlock4Fields(Document payloadDoc, Document mtParsedPayload) {
        List<Map<String, Object>> payloadLevel = toMapList(payloadDoc.get("block4Fields")).stream()
                .map(this::normalizeBlock4FieldRow)
                .collect(Collectors.toList());
        List<Map<String, Object>> parsedLevel = toMapList(mtParsedPayload.get("block4Fields")).stream()
                .map(this::normalizeBlock4FieldRow)
                .collect(Collectors.toList());

        return choosePreferredBlock4Rows(payloadLevel, parsedLevel);
    }

    private Map<String, Object> normalizeBlock4FieldRow(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        row.put("tag", firstNonBlank(
                stringValue(source.get("tag")),
                stringValue(source.get("fieldTag")),
                stringValue(source.get("code")),
                stringValue(source.get("id"))
        ));
        row.put("label", firstNonBlank(
                stringValue(source.get("label")),
                stringValue(source.get("fieldName")),
                stringValue(source.get("name")),
                stringValue(source.get("title")),
                stringValue(source.get("description"))
        ));
        row.put("rawValue", firstNonBlank(
                stringValue(source.get("rawValue")),
                stringValue(source.get("value")),
                stringValue(source.get("fieldValue")),
                stringValue(source.get("text")),
                "â€”"
        ));
        Object components = source.get("components");
        row.put("components", components instanceof Map<?, ?> ? components : Collections.emptyMap());
        row.put("source", firstNonBlank(stringValue(source.get("source")), "existing"));
        return row;
    }

    private List<Map<String, Object>> choosePreferredBlock4Rows(List<Map<String, Object>> primaryRows,
                                                                List<Map<String, Object>> secondaryRows) {
        if (primaryRows == null || primaryRows.isEmpty()) {
            return secondaryRows == null ? Collections.emptyList() : secondaryRows;
        }
        if (secondaryRows == null || secondaryRows.isEmpty()) {
            return primaryRows;
        }
        return scoreBlock4Rows(primaryRows) >= scoreBlock4Rows(secondaryRows) ? primaryRows : secondaryRows;
    }

    private int scoreBlock4Rows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (Map<String, Object> row : rows) {
            String label = stringValue(row.get("label"));
            String tag = stringValue(row.get("tag"));
            String rawValue = stringValue(row.get("rawValue"));
            if (!shouldLookupMtLabel(label)) {
                score += 3;
            }
            if (notBlank(tag)) {
                score += 1;
            }
            if (notBlank(rawValue) && !"â€”".equals(rawValue)) {
                score += 1;
            }
        }
        return score;
    }

    private List<Map<String, Object>> enrichMtBlock4Labels(String messageType, String messageCode, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> normalizedRows = rows.stream()
                .map(this::normalizeBlock4FieldRow)
                .collect(Collectors.toList());

        if (!isMtMessage(messageType, messageCode)) {
            normalizedRows.forEach(this::applyBuiltInLabelIfMissing);
            return normalizedRows;
        }

        List<String> missingTags = normalizedRows.stream()
                .filter(row -> shouldLookupMtLabel(stringValue(row.get("label"))))
                .map(row -> stringValue(row.get("tag")))
                .filter(this::notBlank)
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> mongoLabelsByTag = lookupMtLabels(messageCode, missingTags);

        for (Map<String, Object> row : normalizedRows) {
            String existingLabel = stringValue(row.get("label"));
            if (!shouldLookupMtLabel(existingLabel)) {
                continue;
            }

            String tag = stringValue(row.get("tag"));
            String resolvedLabel = mongoLabelsByTag.get(tag);
            if (!notBlank(resolvedLabel)) {
                resolvedLabel = FIN_TAG_LABELS.get(tag);
            }
            if (notBlank(resolvedLabel)) {
                row.put("label", resolvedLabel);
            }
        }

        return normalizedRows;
    }

    private Map<String, String> lookupMtLabels(String messageCode, List<String> tags) {
        if (!notBlank(messageCode) || tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> messageTypeCandidates = buildMtLabelMessageTypeCandidates(messageCode);
        List<Object> seriesCandidates = buildMtLabelSeriesCandidates(messageCode);
        if (messageTypeCandidates.isEmpty() && seriesCandidates.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Document> lookupClauses = new ArrayList<>();
        if (!messageTypeCandidates.isEmpty()) {
            lookupClauses.add(new Document("messageType", new Document("$in", messageTypeCandidates)));
        }
        if (!seriesCandidates.isEmpty()) {
            lookupClauses.add(new Document("series", new Document("$in", seriesCandidates)));
        }

        Document query = new Document("tag", new Document("$in", tags));
        if (lookupClauses.size() == 1) {
            query.putAll(lookupClauses.get(0));
        } else {
            query.append("$or", lookupClauses);
        }

        List<Document> docs = mongoTemplate.getCollection(appConfig.getMtLabelsCollection())
                .find(query)
                .into(new ArrayList<>());

        Map<String, Integer> messageTypePriority = new HashMap<>();
        for (int i = 0; i < messageTypeCandidates.size(); i++) {
            messageTypePriority.put(messageTypeCandidates.get(i), i);
        }

        Map<String, Integer> seriesPriority = new HashMap<>();
        for (int i = 0; i < seriesCandidates.size(); i++) {
            seriesPriority.put(stringValue(seriesCandidates.get(i)), i);
        }

        Map<String, List<Document>> byTag = docs.stream()
                .filter(doc -> notBlank(doc.getString("tag")) && notBlank(resolveMtLabelValue(doc)))
                .collect(Collectors.groupingBy(doc -> doc.getString("tag")));

        Map<String, String> resolved = new HashMap<>();
        for (String tag : tags) {
            List<Document> candidates = byTag.getOrDefault(tag, Collections.emptyList());
            candidates.stream()
                    .sorted(Comparator
                            .comparingInt((Document doc) -> mtLabelScopePriority(doc, messageTypePriority, seriesPriority))
                            .thenComparingInt(doc -> messageTypePriority.getOrDefault(doc.getString("messageType"), Integer.MAX_VALUE))
                            .thenComparingInt(doc -> seriesPriority.getOrDefault(stringValue(doc.get("series")), Integer.MAX_VALUE))
                            .thenComparingInt(doc -> notBlank(doc.getString("qualifier")) ? 1 : 0)
                            .thenComparing(Comparator.comparingDouble(this::mtLabelProbability).reversed()))
                    .map(this::resolveMtLabelValue)
                    .filter(this::notBlank)
                    .findFirst()
                    .ifPresent(label -> resolved.put(tag, label));
        }
        return resolved;
    }

    private List<Map<String, Object>> enrichMxLabels(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> normalizedRows = rows.stream()
                .map(row -> {
                    Map<String, Object> normalized = new LinkedHashMap<>(row);
                    normalized.put("tag", mxNormalizeTag(stringValue(row.get("tag"))));
                    normalized.put("label", stringValue(row.get("label")));
                    normalized.put("rawValue", firstNonBlank(stringValue(row.get("rawValue")), "—"));
                    normalized.put("path", stringValue(row.get("path")));
                    normalized.put("components", row.getOrDefault("components", Collections.emptyMap()));
                    return normalized;
                })
                .collect(Collectors.toList());

        List<String> tags = normalizedRows.stream()
                .map(row -> mxNormalizeTag(stringValue(row.get("tag"))))
                .filter(this::notBlank)
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> labelsByTag = lookupMxLabels(tags);

        for (Map<String, Object> row : normalizedRows) {
            String currentLabel = stringValue(row.get("label"));
            if (notBlank(currentLabel) && !"—".equals(currentLabel)) {
                continue;
            }
            String tag = mxNormalizeTag(stringValue(row.get("tag")));
            String label = labelsByTag.get(tag);
            if (notBlank(label)) {
                row.put("label", label);
            } else if (notBlank(tag)) {
                row.put("label", toPrettyMxTag(tag));
            }
        }
        return normalizedRows;
    }

    private Map<String, String> lookupMxLabels(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Document> docs = mongoTemplate.getCollection(appConfig.getMxLabelsCollection())
                .find(new Document("tag", new Document("$in", tags)))
                .into(new ArrayList<>());

        Map<String, String> resolved = new HashMap<>();
        for (Document doc : docs) {
            String tag = mxNormalizeTag(doc.getString("tag"));
            String label = firstNonBlank(doc.getString("label"), doc.getString("fieldName"));
            if (notBlank(tag) && notBlank(label) && !resolved.containsKey(tag)) {
                resolved.put(tag, label);
            }
        }
        return resolved;
    }

    private Map<String, String> buildMxNodeLabels(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String tag = mxNormalizeTag(stringValue(row.get("tag")));
            if (notBlank(tag)) {
                tags.add(tag);
            }
            String path = stringValue(row.get("path"));
            if (!notBlank(path)) {
                continue;
            }
            for (String segment : path.split("/")) {
                String normalized = mxNormalizeTag(segment);
                if (notBlank(normalized)) {
                    tags.add(normalized);
                }
            }
        }
        return lookupMxLabels(new ArrayList<>(tags));
    }

    private List<String> buildMtLabelMessageTypeCandidates(String messageCode) {
        if (!notBlank(messageCode)) {
            return Collections.emptyList();
        }
        String trimmed = messageCode.trim().toUpperCase();
        String digits = trimmed.startsWith("MT") ? trimmed.substring(2) : trimmed;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(trimmed);
        if (!digits.isBlank()) {
            candidates.add("MT " + digits);
            candidates.add(digits);
        }
        return new ArrayList<>(candidates);
    }

    private List<Object> buildMtLabelSeriesCandidates(String messageCode) {
        if (!notBlank(messageCode)) {
            return Collections.emptyList();
        }
        String trimmed = messageCode.trim().toUpperCase();
        String digits = trimmed.startsWith("MT") ? trimmed.substring(2) : trimmed;
        digits = digits.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return Collections.emptyList();
        }
        String series = String.valueOf(digits.charAt(0));
        LinkedHashSet<Object> candidates = new LinkedHashSet<>();
        candidates.add(series);
        try {
            candidates.add(Integer.parseInt(series));
        } catch (NumberFormatException ignored) {
            // Keep string candidate only.
        }
        return new ArrayList<>(candidates);
    }

    private int mtLabelScopePriority(Document doc, Map<String, Integer> messageTypePriority, Map<String, Integer> seriesPriority) {
        String messageType = doc.getString("messageType");
        if (notBlank(messageType)) {
            return messageTypePriority.containsKey(messageType) ? 0 : 2;
        }
        String series = stringValue(doc.get("series"));
        if (notBlank(series)) {
            return seriesPriority.containsKey(series) ? 1 : 3;
        }
        return Integer.MAX_VALUE;
    }

    private String resolveMtLabelValue(Document doc) {
        return firstNonBlank(doc.getString("label"), doc.getString("fieldName"));
    }

    private double mtLabelProbability(Document doc) {
        Object probability = doc.get("probability");
        if (probability instanceof Number number) {
            return number.doubleValue();
        }
        if (probability instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0d;
            }
        }
        return 0d;
    }

    private boolean isMtMessage(String messageType, String messageCode) {
        return (notBlank(messageType) && "MT".equalsIgnoreCase(messageType.trim()))
                || (notBlank(messageCode) && messageCode.trim().toUpperCase().startsWith("MT"));
    }

    private boolean isMxMessage(String messageType, String messageCode) {
        return (notBlank(messageType) && "MX".equalsIgnoreCase(messageType.trim()))
                || (notBlank(messageCode) && !messageCode.trim().toUpperCase().startsWith("MT"));
    }

    private String mxTagFromPath(String path) {
        if (!notBlank(path)) {
            return null;
        }
        String normalized = path.trim();
        int slashIndex = normalized.lastIndexOf('/');
        String leaf = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return mxNormalizeTag(leaf);
    }

    private String mxNormalizeTag(String tag) {
        if (!notBlank(tag)) {
            return null;
        }
        String normalized = tag.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        normalized = MX_INDEX_SUFFIX_PATTERN.matcher(normalized).replaceFirst("");
        return stripNamespace(normalized);
    }

    private String stripNamespace(String value) {
        if (!notBlank(value)) {
            return value;
        }
        int colonIndex = value.indexOf(':');
        return colonIndex >= 0 ? value.substring(colonIndex + 1) : value;
    }

    private String toPrettyMxTag(String tag) {
        if (!notBlank(tag)) {
            return null;
        }
        return tag
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .trim();
    }

    private boolean shouldLookupMtLabel(String label) {
        return !notBlank(label) || "â€”".equals(label) || label.toUpperCase().startsWith("TAG ");
    }

    private void applyBuiltInLabelIfMissing(Map<String, Object> row) {
        String label = stringValue(row.get("label"));
        if (!shouldLookupMtLabel(label)) {
            return;
        }
        String tag = stringValue(row.get("tag"));
        if (notBlank(tag) && FIN_TAG_LABELS.containsKey(tag)) {
            row.put("label", FIN_TAG_LABELS.get(tag));
        }
    }

    private String extractBlock4Content(String rawFin) {
        if (!notBlank(rawFin)) {
            return null;
        }

        int blockStart = rawFin.indexOf("{4:");
        if (blockStart >= 0) {
            int contentStart = blockStart + 3;
            int blockEnd = rawFin.indexOf("-}", contentStart);
            String content = blockEnd >= 0
                    ? rawFin.substring(contentStart, blockEnd)
                    : rawFin.substring(contentStart);
            return content.strip();
        }

        return rawFin.strip();
    }

    private String extractBlock1Content(String rawFin) {
        if (!notBlank(rawFin)) {
            return null;
        }

        int blockStart = rawFin.indexOf("{1:");
        if (blockStart < 0) {
            return null;
        }

        int contentStart = blockStart + 3;
        int blockEnd = rawFin.indexOf('}', contentStart);
        if (blockEnd < 0) {
            return rawFin.substring(contentStart).strip();
        }
        return rawFin.substring(contentStart, blockEnd).strip();
    }

    private String extractBlock2Content(String rawFin) {
        if (!notBlank(rawFin)) {
            return null;
        }

        int blockStart = rawFin.indexOf("{2:");
        if (blockStart < 0) {
            return null;
        }

        int contentStart = blockStart + 3;
        int blockEnd = rawFin.indexOf('}', contentStart);
        if (blockEnd < 0) {
            return rawFin.substring(contentStart).strip();
        }
        return rawFin.substring(contentStart, blockEnd).strip();
    }

    private String extractBlock3Content(String rawFin) {
        if (!notBlank(rawFin)) {
            return null;
        }

        int blockStart = rawFin.indexOf("{3:");
        if (blockStart < 0) {
            return null;
        }

        int contentStart = blockStart + 3;
        int depth = 1;
        for (int i = contentStart; i < rawFin.length(); i++) {
            char ch = rawFin.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return rawFin.substring(contentStart, i).strip();
                }
            }
        }
        return rawFin.substring(contentStart).strip();
    }

    private List<Map<String, Object>> buildFinHeaderFields(String messageType,
                                                           String messageCode,
                                                           Document protocolParams,
                                                           Document block1,
                                                           Document block2,
                                                           String rawFin) {
        if (!isMtMessage(messageType, messageCode)) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        String rawBlock1 = extractBlock1Content(rawFin);
        String rawBlock2 = extractBlock2Content(rawFin);

        addFinHeaderRow(rows, parseBlock1TagRow(rawBlock1, "Application ID", 0, 1));
        addFinHeaderRow(rows, parseBlock1TagRow(rawBlock1, "Service ID", 1, 3));
        addFinHeaderRow(rows, parseBlock1TagRow(rawBlock1, "Logical Terminal Address", 3, 15));
        addFinHeaderRow(rows, parseBlock1TagRow(rawBlock1, "Session Number", Math.max(0, safeLength(rawBlock1) - 10), Math.max(0, safeLength(rawBlock1) - 6)));
        addFinHeaderRow(rows, parseBlock1TagRow(rawBlock1, "Sequence Number", Math.max(0, safeLength(rawBlock1) - 6), safeLength(rawBlock1)));

        addFinHeaderRow(rows, finHeaderRow("Direction ID", notBlank(rawBlock2) ? rawBlock2.trim().substring(0, 1) : null));
        addFinHeaderRow(rows, parseBlock2MessageTypeRow(rawBlock2));
        addFinHeaderRow(rows, parseBlock2ReceiverRow(rawBlock2));
        addFinHeaderRow(rows, parseBlock2PriorityRow(rawBlock2));

        String rawBlock3 = extractBlock3Content(rawFin);
        if (notBlank(rawBlock3)) {
            Matcher matcher = Pattern.compile("\\{(\\d{3}):([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(rawBlock3);
            while (matcher.find()) {
                String tag = matcher.group(1);
                String value = matcher.group(2) == null ? null : matcher.group(2).trim();
                if (!notBlank(value)) {
                    continue;
                }
                addFinHeaderRow(rows, "Block 3", tag, value, FIN_BLOCK3_LABELS.getOrDefault(tag, "Tag " + tag));
            }
        }

        return rows;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private Map<String, Object> parseBlock1TagRow(String rawBlock1, String label, int start, int end) {
        if (!notBlank(rawBlock1)) {
            return null;
        }
        String compact = rawBlock1.trim();
        if (start < 0 || end <= start || compact.length() < end) {
            return null;
        }
        return finHeaderRow(label, compact.substring(start, end).trim());
    }

    private Map<String, Object> parseBlock2MessageTypeRow(String rawBlock2) {
        if (!notBlank(rawBlock2)) {
            return null;
        }
        String compact = rawBlock2.trim();
        if (compact.length() < 4) {
            return null;
        }
        char direction = compact.charAt(0);
        if (direction == 'I' || direction == 'O') {
            return finHeaderRow("Message Type", compact.substring(1, 4).trim());
        }
        return null;
    }

    private Map<String, Object> parseBlock2ReceiverRow(String rawBlock2) {
        return finHeaderRow("Destination Address", parseReceiverBicFromBlock2(rawBlock2));
    }

    private Map<String, Object> parseBlock2PriorityRow(String rawBlock2) {
        if (!notBlank(rawBlock2)) {
            return null;
        }
        String compact = rawBlock2.trim();
        char direction = compact.charAt(0);
        if (direction == 'I') {
            if (compact.length() > 16) {
                return finHeaderRow("Message Priority", String.valueOf(compact.charAt(16)));
            }
            return null;
        }
        if (direction == 'O') {
            if (compact.length() > 28) {
                return finHeaderRow("Message Priority", String.valueOf(compact.charAt(28)));
            }
            return null;
        }
        return null;
    }

    private Map<String, Object> finHeaderRow(String label, String value) {
        if (!notBlank(value)) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", "");
        row.put("label", label);
        row.put("rawValue", value.trim());
        return row;
    }

    private void addFinHeaderRow(List<Map<String, Object>> rows, Map<String, Object> row) {
        if (row != null) {
            rows.add(row);
        }
    }

    private void addFinHeaderRow(List<Map<String, Object>> rows, String block, String tag, String value, String label) {
        if (!notBlank(value)) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", tag);
        row.put("label", label);
        row.put("rawValue", value.trim());
        rows.add(row);
    }

    private String extractMurFromRawBlock3(String rawBlock3) {
        if (!notBlank(rawBlock3)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\{108:([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(rawBlock3);
        if (matcher.find()) {
            String value = matcher.group(1);
            return notBlank(value) ? value.trim() : null;
        }
        return null;
    }

    private String extractUetrFromRawBlock3(String rawBlock3) {
        if (!notBlank(rawBlock3)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\{121:([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(rawBlock3);
        if (matcher.find()) {
            String value = matcher.group(1);
            return notBlank(value) ? value.trim() : null;
        }
        return null;
    }

    private String deriveUetr(String messageType,
                              String messageCode,
                              Document doc,
                              Document extractedFields,
                              Document payloadDoc,
                              Document mtParsedPayload,
                              String rawFin) {
        if (isMtMessage(messageType, messageCode)) {
            String rawBlock3 = firstNonBlank(
                    mtParsedPayload.getString("rawBlock3"),
                    payloadDoc.getString("rawBlock3"),
                    extractBlock3Content(rawFin)
            );
            return firstNonBlank(
                    extractUetrFromRawBlock3(rawBlock3),
                    doc.getString("uetr"),
                    extractedFields.getString("uetr"),
                    payloadDoc.getString("uetr")
            );
        }
        return firstNonBlank(
                doc.getString("uetr"),
                extractedFields.getString("uetr"),
                payloadDoc.getString("uetr"),
                documentAt(payloadDoc, "mxExtractedFields").getString("uetr")
        );
    }

    private String deriveMur(Document doc,
                             String messageType,
                             String messageCode,
                             Document protocolParams,
                             Document payloadDoc,
                             Document mtParsedPayload,
                             String rawFin) {
        if (isMtMessage(messageType, messageCode)) {
            String rawBlock3 = firstNonBlank(
                    mtParsedPayload.getString("rawBlock3"),
                    payloadDoc.getString("rawBlock3"),
                    extractBlock3Content(rawFin)
            );
            return firstNonBlank(
                    extractMurFromRawBlock3(rawBlock3),
                    mtParsedPayload.getString("messageReference"),
                    payloadDoc.getString("messageReference"),
                    payloadDoc.getString("msgRef"),
                    protocolParams.getString("messageReference"),
                    mtParsedPayload.getString("transactionReference"),
                    payloadDoc.getString("transactionReference"),
                    doc.getString("messageReference"),
                    documentAt(doc, "header").getString("messageReference"),
                    protocolParams.getString("userReference")
            );
        }
        return firstNonBlank(protocolParams.getString("userReference"));
    }

    private String resolveSenderBic(Document doc, Document protocolParams, Document payloadDoc) {
        String payloadMessageFamily = firstNonBlank(payloadDoc.getString("messageFamily"), doc.getString("messageFamily"));
        if ("MX".equalsIgnoreCase(payloadMessageFamily)) {
            return firstNonBlank(
                    documentAt(payloadDoc, "applicationHeader").getString("fromBic"),
                    documentAt(doc, "applicationHeader").getString("fromBic")
            );
        }
        Document mtParsedPayload = documentAt(payloadDoc, "mtParsedPayload");
        Document block1 = documentAt(mtParsedPayload, "block1");
        return firstNonBlank(
                documentAt(payloadDoc, "applicationHeader").getString("fromBic"),
                documentAt(doc, "applicationHeader").getString("fromBic"),
                protocolParams.getString("requester"),
                protocolParams.getString("requestor"),
                doc.getString("senderAddress"),
                documentAt(doc, "header").getString("senderAddress"),
                block1.getString("logicalTerminalAddress"),
                payloadDoc.getString("senderAddress")
        );
    }

    private String resolveReceiverBic(Document doc, Document protocolParams, Document payloadDoc) {
        String payloadMessageFamily = firstNonBlank(payloadDoc.getString("messageFamily"), doc.getString("messageFamily"));
        if ("MX".equalsIgnoreCase(payloadMessageFamily)) {
            return firstNonBlank(
                    documentAt(payloadDoc, "applicationHeader").getString("toBic"),
                    documentAt(doc, "applicationHeader").getString("toBic")
            );
        }
        return firstNonBlank(
                documentAt(payloadDoc, "applicationHeader").getString("toBic"),
                documentAt(doc, "applicationHeader").getString("toBic"),
                protocolParams.getString("responder")
        );
    }

    private String parseSenderBicFromBlock1(String rawBlock1) {
        if (!notBlank(rawBlock1)) {
            return null;
        }
        String compact = rawBlock1.trim();
        if (compact.length() < 15) {
            return null;
        }
        return compact.substring(3, 15);
    }

    private String parseReceiverBicFromBlock2(String rawBlock2) {
        if (!notBlank(rawBlock2)) {
            return null;
        }
        String compact = rawBlock2.trim();
        if (compact.length() < 16) {
            return null;
        }
        char direction = compact.charAt(0);
        if (direction == 'I' || direction == 'O') {
            int start = direction == 'O' ? 16 : 4;
            int end = Math.min(start + 12, compact.length());
            if (end > start) {
                return compact.substring(start, end);
            }
        }
        return null;
    }

    private List<String> distinctBics(String messagesCol, String payloadsCol, boolean sender) {
        Set<String> values = new LinkedHashSet<>();
        FindIterable<Document> mtPayloadDocs = mongoTemplate.getCollection(payloadsCol)
                .find(new Document("messageFamily", "MT"))
                .projection(new Document("mtParsedPayload.rawBlock1", 1)
                        .append("mtParsedPayload.rawBlock2", 1)
                        .append("mtParsedPayload.block1.logicalTerminalAddress", 1)
                        .append("mtParsedPayload.block2.receiverAddress", 1)
                        .append("rawFin", 1)
                        .append("senderAddress", 1)
                        .append("receiverAddress", 1));

        for (Document doc : mtPayloadDocs) {
            Document mtParsedPayload = documentAt(doc, "mtParsedPayload");
            Document block1 = documentAt(mtParsedPayload, "block1");
            Document block2 = documentAt(mtParsedPayload, "block2");
            String rawFin = firstNonBlank(doc.getString("rawFin"), mtParsedPayload.getString("rawFin"), composeRawFin(mtParsedPayload));
            String rawBlock1 = firstNonBlank(mtParsedPayload.getString("rawBlock1"), extractBlock1Content(rawFin));
            String rawBlock2 = firstNonBlank(mtParsedPayload.getString("rawBlock2"), extractBlock2Content(rawFin));
            String value = sender
                    ? firstNonBlank(
                    parseSenderBicFromBlock1(rawBlock1),
                    block1.getString("logicalTerminalAddress"),
                    doc.getString("senderAddress")
            )
                    : firstNonBlank(
                    parseReceiverBicFromBlock2(rawBlock2),
                    block2.getString("receiverAddress"),
                    doc.getString("receiverAddress")
            );
            if (notBlank(value)) {
                values.add(value.trim());
            }
        }

        FindIterable<Document> docs = mongoTemplate.getCollection(payloadsCol)
                .find()
                .projection(new Document("applicationHeader.fromBic", 1)
                        .append("applicationHeader.toBic", 1));

        for (Document doc : docs) {
            Document appHeader = documentAt(doc, "applicationHeader");
            String value = sender
                    ? firstNonBlank(
                    appHeader.getString("fromBic")
            )
                    : firstNonBlank(
                    appHeader.getString("toBic")
            );
            if (notBlank(value)) {
                values.add(value.trim());
            }
        }
        return values.stream().sorted().collect(Collectors.toList());
    }

    private String parseSessionNumberFromBlock1(String rawBlock1) {
        if (!notBlank(rawBlock1)) {
            return null;
        }
        String compact = rawBlock1.trim();
        if (compact.length() < 10) {
            return null;
        }
        return compact.substring(compact.length() - 10, compact.length() - 6);
    }

    private String parseSequenceNumberFromBlock1(String rawBlock1) {
        if (!notBlank(rawBlock1)) {
            return null;
        }
        String compact = rawBlock1.trim();
        if (compact.length() < 6) {
            return null;
        }
        return compact.substring(compact.length() - 6);
    }

    private String cleanBlock4Value(String value) {
        if (!notBlank(value)) {
            return null;
        }
        String cleaned = value.replaceAll("\\s*-}$", "").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private Integer computePayloadFieldCount(Document mtParsedPayload, List<Map<String, Object>> block4Fields) {
        Document rawFields = documentAt(mtParsedPayload, "rawFields");
        if (!rawFields.isEmpty()) {
            int count = (int) rawFields.keySet().stream().filter(key -> !"_raw".equals(key)).count();
            return count == 0 ? null : count;
        }
        return block4Fields == null || block4Fields.isEmpty() ? null : block4Fields.size();
    }

    private String composeRawFin(Document mtParsedPayload) {
        String rawBlock1 = mtParsedPayload.getString("rawBlock1");
        String rawBlock2 = mtParsedPayload.getString("rawBlock2");
        String rawBlock4 = mtParsedPayload.getString("rawBlock4");
        if (!notBlank(rawBlock1) && !notBlank(rawBlock2) && !notBlank(rawBlock4)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (notBlank(rawBlock1)) {
            builder.append("{1:").append(rawBlock1).append("}");
        }
        if (notBlank(rawBlock2)) {
            builder.append("{2:").append(rawBlock2).append("}");
        }
        if (notBlank(rawBlock4)) {
            builder.append("{4:\n").append(rawBlock4);
            if (!rawBlock4.endsWith("-}")) {
                builder.append("-}");
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> toMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }

        return list.stream()
                .map(item -> {
                    if (item instanceof Document document) {
                        return new LinkedHashMap<String, Object>(document);
                    }
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> converted = new LinkedHashMap<>();
                        map.forEach((key, itemValue) -> converted.put(String.valueOf(key), itemValue));
                        return converted;
                    }
                    return null;
                })
                .filter(item -> item != null && !item.isEmpty())
                .collect(Collectors.toList());
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return mongoTemplate.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private long aggregateCount(String collection, List<Document> pipeline, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            CachedValue<Long> cache = unfilteredCountCache;
            if (cache != null && !cache.isExpired(appConfig.getMetadataCacheTtlMs())) {
                return cache.value();
            }
        }
        List<Document> countPipeline = new ArrayList<>(pipeline);
        countPipeline.add(new Document("$count", "total"));
        List<Document> countRows = aggregate(collection, countPipeline);
        long total;
        if (countRows.isEmpty()) {
            total = 0L;
        } else {
            Object totalObj = countRows.get(0).get("total");
            total = totalObj instanceof Number number ? number.longValue() : 0L;
        }
        if (filters == null || filters.isEmpty()) {
            unfilteredCountCache = new CachedValue<>(total, System.currentTimeMillis());
        }
        return total;
    }

    private long countMessages(String collection, Document match, Map<String, String> filters) {
        if ((filters == null || filters.isEmpty()) && (match == null || match.isEmpty())) {
            CachedValue<Long> cache = unfilteredCountCache;
            if (cache != null && !cache.isExpired(appConfig.getMetadataCacheTtlMs())) {
                return cache.value();
            }
        }

        long total = (match == null || match.isEmpty())
                ? mongoTemplate.getCollection(collection).countDocuments()
                : mongoTemplate.getCollection(collection).countDocuments(match);

        if (filters == null || filters.isEmpty()) {
            unfilteredCountCache = new CachedValue<>(total, System.currentTimeMillis());
        }
        return total;
    }

    private PageSlice<Document> findMessagePage(String collection, Document match, int page, int pageSize, String cursor) {
        Document effectiveMatch = mergeMatch(match, buildCursorMatch(cursor));
        var finder = mongoTemplate.getCollection(collection).find(effectiveMatch == null ? new Document() : effectiveMatch)
                .sort(SEARCH_SORT)
                .limit(pageSize + 1);

        if (!notBlank(cursor) && page > 0) {
            finder = finder.skip(page * pageSize);
        }

        List<Document> docs = finder.into(new ArrayList<>());
        return toPageSlice(docs, pageSize);
    }

    private PageSlice<Document> findLookupPage(String collection, SearchPlan plan, int page, int pageSize, String cursor) {
        Document effectiveMessageMatch = mergeMatch(plan.messageMatch(), buildCursorMatch(cursor));
        List<Document> rowsPipeline = new ArrayList<>(buildLookupPipeline(effectiveMessageMatch, plan.postLookupMatch()));
        rowsPipeline.add(new Document("$sort", SEARCH_SORT));
        if (!notBlank(cursor) && page > 0) {
            rowsPipeline.add(new Document("$skip", (long) page * pageSize));
        }
        rowsPipeline.add(new Document("$limit", pageSize + 1));
        List<Document> docs = aggregate(collection, rowsPipeline);
        return toPageSlice(docs, pageSize);
    }

    private Map<String, Document> fetchPayloadsByReference(List<Document> docs) {
        List<String> references = docs.stream()
                .map(doc -> stringValue(doc.get("messageReference")))
                .filter(this::notBlank)
                .distinct()
                .collect(Collectors.toList());
        if (references.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Document> payloadByReference = new LinkedHashMap<>();
        int batchSize = Math.max(1, appConfig.getPayloadFetchBatchSize());
        String payloadCollection = appConfig.getPayloadsCollection();

        for (int start = 0; start < references.size(); start += batchSize) {
            List<String> batch = references.subList(start, Math.min(start + batchSize, references.size()));
            List<Document> payloads = mongoTemplate.getCollection(payloadCollection)
                    .find(new Document("messageReference", new Document("$in", batch)))
                    .into(new ArrayList<>());
            for (Document payload : payloads) {
                String reference = stringValue(payload.get("messageReference"));
                if (notBlank(reference) && !payloadByReference.containsKey(reference)) {
                    payloadByReference.put(reference, payload);
                }
            }
        }

        return payloadByReference;
    }

    private void appendExportBatch(List<SearchResponse> target, List<Document> docs) {
        appendExportBatch(docs, target::add);
    }

    private void appendExportBatch(List<Document> docs, Consumer<SearchResponse> consumer) {
        Map<String, Document> payloadByReference = fetchPayloadsByReference(docs);
        docs.stream()
                .map(doc -> toResponse(withPayloadDoc(doc, payloadByReference.get(stringValue(doc.get("messageReference"))))))
                .forEach(consumer);
    }

    private void forEachExportResponse(Map<String, String> filters, Consumer<SearchResponse> consumer) {
        String messagesCol = appConfig.getSwiftCollection();
        SearchPlan plan = buildSearchPlan(filters);
        int batchSize = Math.max(1, appConfig.getExportFetchBatchSize());

        if (appConfig.isOptimizeWithoutLookup() && !plan.requiresLookup()) {
            List<Document> batch = new ArrayList<>(batchSize);
            FindIterable<Document> iterable = mongoTemplate.getCollection(messagesCol)
                    .find(plan.messageMatch().isEmpty() ? new Document() : plan.messageMatch())
                    .sort(SEARCH_SORT)
                    .batchSize(batchSize);

            for (Document doc : iterable) {
                batch.add(doc);
                if (batch.size() >= batchSize) {
                    appendExportBatch(batch, consumer);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                appendExportBatch(batch, consumer);
            }
            return;
        }

        for (Document doc : mongoTemplate.getCollection(messagesCol)
                .aggregate(buildLookupPipeline(plan))
                .allowDiskUse(true)
                .batchSize(batchSize)) {
            consumer.accept(toResponse(doc));
        }
    }

    private void streamResultTableCsv(Map<String, String> filters,
                                      List<ExportColumnRequest> columns,
                                      OutputStream outputStream) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            writer.write(columns.stream()
                    .map(ExportColumnRequest::getLabel)
                    .map(this::escapeCsvCell)
                    .collect(Collectors.joining(",")));
            writer.newLine();

            try {
                forEachExportResponse(filters, response -> writeCsvRow(writer, columns, response));
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
            writer.flush();
        }
    }

    private void streamResultTableExcel(Map<String, String> filters,
                                        List<ExportColumnRequest> columns,
                                        OutputStream outputStream) throws IOException {
        int maxRowsPerSheet = Math.max(1, Math.min(EXCEL_HARD_MAX_ROWS_PER_SHEET, appConfig.getExportExcelMaxRowsPerSheet()));

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(500)) {
            workbook.setCompressTempFiles(true);

            ExcelSheetCursor cursor = createExcelSheet(workbook, columns, 1);
            try {
                forEachExportResponse(filters, response -> {
                    if (cursor.dataRows >= maxRowsPerSheet) {
                        cursor.sheetIndex += 1;
                        cursor.sheet = createSheetWithHeader(workbook, columns, cursor.sheetIndex);
                        cursor.rowIndex = 1;
                        cursor.dataRows = 0;
                    }
                    Row row = cursor.sheet.createRow(cursor.rowIndex++);
                    writeExcelRow(row, columns, response);
                    cursor.dataRows += 1;
                });
                workbook.write(outputStream);
                outputStream.flush();
            } finally {
                workbook.dispose();
            }
        }
    }

    private ExcelSheetCursor createExcelSheet(SXSSFWorkbook workbook, List<ExportColumnRequest> columns, int sheetIndex) {
        Sheet sheet = createSheetWithHeader(workbook, columns, sheetIndex);
        return new ExcelSheetCursor(sheet, sheetIndex, 1, 0);
    }

    private Sheet createSheetWithHeader(SXSSFWorkbook workbook, List<ExportColumnRequest> columns, int sheetIndex) {
        Sheet sheet = workbook.createSheet(sheetIndex == 1 ? "Export" : "Export " + sheetIndex);
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            headerRow.createCell(i).setCellValue(toExcelCellValue(columns.get(i).getLabel()));
            sheet.setColumnWidth(i, 24 * 256);
        }
        return sheet;
    }

    private void writeCsvRow(BufferedWriter writer, List<ExportColumnRequest> columns, SearchResponse response) {
        try {
            String line = columns.stream()
                    .map(column -> escapeCsvCell(resolveExportColumnValue(response, column.getKey())))
                    .collect(Collectors.joining(","));
            writer.write(line);
            writer.newLine();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeExcelRow(Row row, List<ExportColumnRequest> columns, SearchResponse response) {
        for (int i = 0; i < columns.size(); i++) {
            row.createCell(i).setCellValue(toExcelCellValue(resolveExportColumnValue(response, columns.get(i).getKey())));
        }
    }

    private String toExcelCellValue(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = EXCEL_ILLEGAL_XML_CHARS.matcher(value).replaceAll("");
        return sanitized.length() <= EXCEL_MAX_CELL_CHARS ? sanitized : sanitized.substring(0, EXCEL_MAX_CELL_CHARS);
    }

    private List<ExportColumnRequest> normalizeExportColumns(List<ExportColumnRequest> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of(new ExportColumnRequest("reference", "Reference"));
        }
        Map<String, ExportColumnRequest> unique = new LinkedHashMap<>();
        for (ExportColumnRequest column : columns) {
            if (column == null || !notBlank(column.getKey())) continue;
            String key = column.getKey().trim();
            String label = notBlank(column.getLabel()) ? column.getLabel().trim() : key;
            unique.putIfAbsent(key, new ExportColumnRequest(key, label));
        }
        if (unique.isEmpty()) {
            return List.of(new ExportColumnRequest("reference", "Reference"));
        }
        return List.copyOf(unique.values());
    }

    private String resolveExportColumnValue(SearchResponse response, String key) {
        if (response == null || !notBlank(key)) return "";
        return switch (key) {
            case "reference" -> stringifyExportValue(buildExportReference(response));
            case "format" -> stringifyExportValue(normalizeExportFormat(response.getFormat()));
            case "type" -> stringifyExportValue(response.getType());
            default -> stringifyExportValue(readSearchResponseField(response, key));
        };
    }

    private String buildExportReference(SearchResponse response) {
        if (response == null) return "";
        String uetr = response.getUetr();
        String id = firstNonBlank(response.getId(),
                response.getSequenceNumber() == null ? null : String.valueOf(response.getSequenceNumber()));
        return firstNonBlank(
                response.getReference(),
                response.getMur(),
                response.getTransactionReference(),
                response.getTransferReference(),
                response.getRelatedReference(),
                response.getUserReference(),
                response.getReference(),
                uetr == null ? null : "UETR-" + uetr.substring(0, Math.min(8, uetr.length())).toUpperCase(),
                id == null ? null : "ID-" + id.substring(0, Math.min(10, id.length()))
        );
    }

    private String normalizeExportFormat(String rawFormat) {
        if (!notBlank(rawFormat)) return rawFormat;
        return rawFormat.replace("ALL-MT&MX", "ALL MT&MX");
    }

    private Object readSearchResponseField(SearchResponse response, String key) {
        Field field = SEARCH_RESPONSE_FIELDS.get(key);
        if (field == null) return null;
        try {
            return field.get(response);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private String stringifyExportValue(Object value) {
        if (value == null) return "";
        if (value instanceof String str) return str;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return value instanceof Instant instant ? instant.toString() : stringifyFallback(value);
    }

    private String stringifyFallback(Object value) {
        try {
            return value instanceof Map || value instanceof List ? new Document("value", value).toJson().replaceFirst("^\\{\"value\":", "").replaceFirst("}$", "") : String.valueOf(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String escapeCsvCell(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static Map<String, Field> initSearchResponseFields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Field field : SearchResponse.class.getDeclaredFields()) {
            field.setAccessible(true);
            fields.put(field.getName(), field);
        }
        return Map.copyOf(fields);
    }

    private static final class ExcelSheetCursor {
        private Sheet sheet;
        private int sheetIndex;
        private int rowIndex;
        private int dataRows;

        private ExcelSheetCursor(Sheet sheet, int sheetIndex, int rowIndex, int dataRows) {
            this.sheet = sheet;
            this.sheetIndex = sheetIndex;
            this.rowIndex = rowIndex;
            this.dataRows = dataRows;
        }
    }

    private Document withPayloadDoc(Document doc, Document payloadDoc) {
        Document enriched = new Document(doc);
        if (payloadDoc != null && !payloadDoc.isEmpty()) {
            enriched.put(PAYLOAD_ALIAS, payloadDoc);
        }
        return enriched;
    }

    private List<String> distinct(String collection, String fieldPath) {
        try {
            return mongoTemplate.findDistinct(new Query(), fieldPath, collection, String.class).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> distinctMerged(String collection, String... fieldPaths) {
        Set<String> merged = new LinkedHashSet<>();
        for (String fieldPath : fieldPaths) {
            merged.addAll(distinct(collection, fieldPath));
        }
        return merged.stream().sorted().collect(Collectors.toList());
    }

    private List<String> distinctMessageCodesByFamily(String collection, String family) {
        try {
            List<Document> pipeline = List.of(
                    new Document("$project", new Document("messageFamily",
                            new Document("$ifNull", Arrays.asList("$messageFamily", "$header.messageFamily")))
                            .append("messageTypeCode",
                                    new Document("$ifNull", Arrays.asList("$messageTypeCode", "$header.messageTypeCode")))),
                    new Document("$match", new Document("messageFamily", family)
                            .append("messageTypeCode", new Document("$nin", Arrays.asList(null, "")))),
                    new Document("$group", new Document("_id", "$messageTypeCode")),
                    new Document("$sort", new Document("_id", 1))
            );

            return mongoTemplate.getCollection(collection)
                    .aggregate(pipeline)
                    .into(new ArrayList<>())
                    .stream()
                    .map(doc -> stringValue(doc.get("_id")))
                    .filter(this::notBlank)
                    .collect(Collectors.toList());
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private Document regexClause(String fieldPath, String value) {
        return new Document(fieldPath, new Document("$regex", escapeRegex(value)).append("$options", "i"));
    }

    private Document prefixRegexClause(String fieldPath, String value) {
        return new Document(fieldPath, new Document("$regex", "^" + escapeRegex(value)).append("$options", "i"));
    }

    private List<String> buildMessageCodeSearchPatterns(String value) {
        String trimmed = value == null ? "" : value.trim().toUpperCase();
        if (trimmed.isBlank()) {
            return Collections.emptyList();
        }

        String compact = trimmed.replaceAll("\\s+", "");
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        patterns.add("^" + escapeRegex(trimmed));
        if (!compact.equals(trimmed)) {
            patterns.add("^" + escapeRegex(compact));
        }

        if (compact.startsWith("MT") && compact.length() > 2) {
            String suffix = compact.substring(2);
            patterns.add("^" + escapeRegex(suffix));
            patterns.add("^MT\\s*" + escapeRegex(suffix));
        } else if (compact.matches("\\d{1,3}[A-Z]?$")) {
            patterns.add("^" + escapeRegex(compact));
            patterns.add("^MT\\s*" + escapeRegex(compact));
        }

        return new ArrayList<>(patterns);
    }

    private Document regexCondition(String fieldPath, String value) {
        return new Document(fieldPath, new Document("$regex", escapeRegex(value)).append("$options", "i"));
    }

    private Object decimalExpr(String... fieldPaths) {
        return new Document("$toDouble",
                new Document("$replaceAll",
                        new Document("input", coalesceFieldExpression("0", fieldPaths))
                                .append("find", ",")
                                .append("replacement", ".")
                )
        );
    }

    private Object integerExpr(String... fieldPaths) {
        return new Document("$toInt", coalesceFieldExpression("0", fieldPaths));
    }

    private Object coalesceFieldExpression(String defaultValue, String... fieldPaths) {
        Object expression = defaultValue;
        for (int i = fieldPaths.length - 1; i >= 0; i--) {
            expression = new Document("$ifNull", Arrays.asList("$" + fieldPaths[i], expression));
        }
        return expression;
    }

    private Document documentAt(Document source, String key) {
        Object value = source.get(key);
        return value instanceof Document document ? document : new Document();
    }

    private Object mtParsedPayloadAt(Document mtParsedPayload, String objectKey, String innerKey) {
        Document nested = documentAt(mtParsedPayload, objectKey);
        Object value = nested.get(innerKey);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return value;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> item != null && !item.isBlank()).collect(Collectors.toList());
        }
        String scalar = stringValue(value);
        return scalar == null || scalar.isBlank() ? Collections.emptyList() : Collections.singletonList(scalar);
    }

    private Double parseFlexibleDouble(String value) {
        if (!notBlank(value)) {
            return null;
        }
        String normalized = value.replace(" ", "");
        if (normalized.contains(",") && !normalized.contains(".")) {
            normalized = normalized.replace(",", ".");
        } else if (normalized.contains(",") && normalized.contains(".")) {
            normalized = normalized.replace(",", "");
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void parseIntStr(String value, Consumer<Integer> setter) {
        if (!notBlank(value)) {
            return;
        }
        try {
            setter.accept(Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseIntObj(Object value, Consumer<Integer> setter) {
        if (value instanceof Number number) {
            setter.accept(number.intValue());
            return;
        }
        parseIntStr(stringValue(value), setter);
    }

    private Object firstNonBlankObject(Object... values) {
        for (Object value : values) {
            if (value instanceof String stringValue) {
                if (notBlank(stringValue)) {
                    return stringValue;
                }
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document document) {
            if (document.containsKey("$oid")) {
                return stringValue(document.get("$oid"));
            }
            if (document.containsKey("$date")) {
                return stringValue(document.get("$date"));
            }
            if (document.containsKey("$numberLong")) {
                return stringValue(document.get("$numberLong"));
            }
        }
        return String.valueOf(value);
    }

    private String escapeRegex(String value) {
        return value.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }

    private PageSlice<Document> toPageSlice(List<Document> docs, int pageSize) {
        boolean hasNext = docs.size() > pageSize;
        List<Document> pageDocs = hasNext ? new ArrayList<>(docs.subList(0, pageSize)) : docs;
        String nextCursor = hasNext && !pageDocs.isEmpty() ? encodeCursor(pageDocs.get(pageDocs.size() - 1)) : null;
        return new PageSlice<>(pageDocs, hasNext, nextCursor);
    }

    private Document mergeMatch(Document primary, Document additional) {
        boolean primaryEmpty = primary == null || primary.isEmpty();
        boolean additionalEmpty = additional == null || additional.isEmpty();
        if (primaryEmpty) {
            return additionalEmpty ? new Document() : additional;
        }
        if (additionalEmpty) {
            return primary;
        }
        return new Document("$and", List.of(primary, additional));
    }

    private String encodeCursor(Document doc) {
        if (doc == null) {
            return null;
        }
        String headerDateCreated = stringValue(documentAt(doc, "header").get("dateCreated"));
        String dateCreated = stringValue(doc.get("dateCreated"));
        String id = stringValue(doc.get("_id"));
        if (!notBlank(id)) {
            return null;
        }
        String raw = String.join("\n",
                headerDateCreated == null ? "" : headerDateCreated,
                dateCreated == null ? "" : dateCreated,
                id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Document buildCursorMatch(String cursorToken) {
        SearchCursor cursor = decodeCursor(cursorToken);
        if (cursor == null || !notBlank(cursor.id())) {
            return new Document();
        }

        Object cursorId = parseCursorId(cursor.id());
        List<Document> orClauses = new ArrayList<>();

        if (notBlank(cursor.headerDateCreated())) {
            orClauses.add(new Document("header.dateCreated", new Document("$lt", cursor.headerDateCreated())));

            if (notBlank(cursor.dateCreated())) {
                orClauses.add(new Document("$and", List.of(
                        new Document("header.dateCreated", cursor.headerDateCreated()),
                        new Document("dateCreated", new Document("$lt", cursor.dateCreated()))
                )));

                orClauses.add(new Document("$and", List.of(
                        new Document("header.dateCreated", cursor.headerDateCreated()),
                        new Document("dateCreated", cursor.dateCreated()),
                        new Document("_id", new Document("$lt", cursorId))
                )));
            } else {
                orClauses.add(new Document("$and", List.of(
                        new Document("header.dateCreated", cursor.headerDateCreated()),
                        new Document("_id", new Document("$lt", cursorId))
                )));
            }
        } else if (notBlank(cursor.dateCreated())) {
            orClauses.add(new Document("dateCreated", new Document("$lt", cursor.dateCreated())));
            orClauses.add(new Document("$and", List.of(
                    new Document("dateCreated", cursor.dateCreated()),
                    new Document("_id", new Document("$lt", cursorId))
            )));
        } else {
            orClauses.add(new Document("_id", new Document("$lt", cursorId)));
        }

        return orClauses.size() == 1 ? orClauses.get(0) : new Document("$or", orClauses);
    }

    private SearchCursor decodeCursor(String cursorToken) {
        if (!notBlank(cursorToken)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursorToken), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", -1);
            if (parts.length < 3) {
                return null;
            }
            return new SearchCursor(blankToNull(parts[0]), blankToNull(parts[1]), blankToNull(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Object parseCursorId(String rawId) {
        return ObjectId.isValid(rawId) ? new ObjectId(rawId) : rawId;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String dateOnly(String isoValue) {
        if (isoValue == null || isoValue.length() < 10) {
            return null;
        }
        return isoValue.substring(0, 10).replace("-", "/");
    }

    private String timeOnly(String isoValue) {
        if (isoValue == null || isoValue.length() < 19) {
            return null;
        }
        return isoValue.substring(11, 19);
    }

    private record SearchPlan(Document messageMatch, Document postLookupMatch, boolean requiresLookup) {
    }

    private record SearchCursor(String headerDateCreated, String dateCreated, String id) {
    }

    private record PageSlice<T>(List<T> items, boolean hasNext, String nextCursor) {
    }

    private record DropdownFieldSpec(
            String key,
            BiConsumer<DropdownOptionsResponse, List<String>> setter,
            Function<DropdownOptionsResponse, List<String>> getter
    ) {
    }

    private record CachedValue<T>(T value, long loadedAtMs) {
        private boolean isExpired(long ttlMs) {
            return ttlMs <= 0 || (System.currentTimeMillis() - loadedAtMs) > ttlMs;
        }
    }
}


