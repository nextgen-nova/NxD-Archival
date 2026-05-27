package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchIndexInitializer {

    private final MongoTemplate mongoTemplate;
    private final AppConfig appConfig;

    @PostConstruct
    public void ensureIndexes() {
        if (!appConfig.isEnsureIndexes()) {
            return;
        }

        var messages = mongoTemplate.indexOps(appConfig.getSwiftCollection());
        messages.ensureIndex(new Index().on("messageReference", Sort.Direction.ASC).named("msg_ref_idx"));
        messages.ensureIndex(new Index().on("header.messageReference", Sort.Direction.ASC).named("msg_hdr_ref_idx"));
        messages.ensureIndex(new Index().on("transactionReference", Sort.Direction.ASC).named("msg_txn_ref_idx"));
        messages.ensureIndex(new Index().on("header.transactionReference", Sort.Direction.ASC).named("msg_hdr_txn_ref_idx"));
        messages.ensureIndex(new Index().on("uetr", Sort.Direction.ASC).named("msg_uetr_idx"));
        messages.ensureIndex(new Index().on("header.dateCreated", Sort.Direction.DESC).on("dateCreated", Sort.Direction.DESC).on("_id", Sort.Direction.DESC).named("msg_created_sort_cursor_idx"));
        messages.ensureIndex(new Index().on("messageTypeCode", Sort.Direction.ASC).named("msg_type_code_idx"));
        messages.ensureIndex(new Index().on("header.messageTypeCode", Sort.Direction.ASC).named("msg_hdr_type_code_idx"));
        messages.ensureIndex(new Index().on("currentStatus", Sort.Direction.ASC).named("msg_status_idx"));
        messages.ensureIndex(new Index().on("status.current", Sort.Direction.ASC).named("msg_status_nested_idx"));
        messages.ensureIndex(new Index().on("direction", Sort.Direction.ASC).named("msg_direction_idx"));
        messages.ensureIndex(new Index().on("header.direction", Sort.Direction.ASC).named("msg_hdr_direction_idx"));
        messages.ensureIndex(new Index().on("senderAddress", Sort.Direction.ASC).named("msg_sender_idx"));
        messages.ensureIndex(new Index().on("receiverAddress", Sort.Direction.ASC).named("msg_receiver_idx"));
        messages.ensureIndex(new Index().on("header.senderAddress", Sort.Direction.ASC).named("msg_hdr_sender_idx"));
        messages.ensureIndex(new Index().on("header.receiverAddress", Sort.Direction.ASC).named("msg_hdr_receiver_idx"));
        messages.ensureIndex(new Index().on("protocolParams.sequenceNumber", Sort.Direction.ASC).named("msg_seq_idx"));
        messages.ensureIndex(new Index().on("protocolParams.sessionNumber", Sort.Direction.ASC).named("msg_session_idx"));
        messages.ensureIndex(new Index().on("finSequenceNumber", Sort.Direction.ASC).named("msg_fin_seq_idx"));
        messages.ensureIndex(new Index().on("finSessionNumber", Sort.Direction.ASC).named("msg_fin_session_idx"));
        messages.ensureIndex(new Index().on("finLogicalTerminal", Sort.Direction.ASC).named("msg_fin_lt_idx"));
        messages.ensureIndex(new Index().on("protocolParams.logicalTerminal", Sort.Direction.ASC).named("msg_protocol_lt_idx"));

        var payloads = mongoTemplate.indexOps(appConfig.getPayloadsCollection());
        payloads.ensureIndex(new Index().on("messageReference", Sort.Direction.ASC).named("payload_ref_idx"));
        payloads.ensureIndex(new Index().on("currency", Sort.Direction.ASC).named("payload_currency_idx"));
        payloads.ensureIndex(new Index().on("mtParsedPayload.block1.sequenceNumber", Sort.Direction.ASC).named("payload_seq_idx"));
        payloads.ensureIndex(new Index().on("mtParsedPayload.block1.sessionNumber", Sort.Direction.ASC).named("payload_session_idx"));
        payloads.ensureIndex(new Index().on("mtParsedPayload.transactionReference", Sort.Direction.ASC).named("payload_mur_idx"));
        payloads.ensureIndex(new Index().on("mtParsedPayload.block1.logicalTerminalAddress", Sort.Direction.ASC).named("payload_lt_idx"));
    }
}
