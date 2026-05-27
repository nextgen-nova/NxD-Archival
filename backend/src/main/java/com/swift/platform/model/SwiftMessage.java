package com.swift.platform.model;

import lombok.Data;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Flat document model for the "messages" collection (ampdb.messages).
 *
 * NEW SCHEMA — fields are at the TOP LEVEL (no "message" subdocument wrapper).
 *
 * Key field mapping from old jason_swift → new messages:
 *   messageType  (old message.messageType)   → messageFamily
 *   messageCode  (old message.messageCode)   → messageTypeCode
 *   io           (old message.io)            → direction
 *   status       (old message.status)        → currentStatus
 *   phase        (old message.phase)         → statusPhase
 *   action       (old message.action)        → statusAction
 *   reason       (old message.reason)        → statusReason
 *   creationDate (old message.creationDate)  → dateCreated
 *   receivedDT   (old message.receivedDT)    → dateReceived
 *   sender       (old message.sender)        → senderAddress
 *   receiver     (old message.receiver)      → receiverAddress
 *   reference    (old message.reference)     → messageReference
 *   amount       (old message.amount)        → ampAmount  (String)
 *   ccy          (old message.ccy)           → ampCurrency
 *   valueDate    (old message.valueDate)     → ampValueDate
 *   networkProtocol (old)                    → protocol
 *   deliveryMode (old)                       → communicationType
 *   sequenceNumber (old)                     → finSequenceNumber
 *   messagePriority (old)                    → finMessagePriority
 *   backendChannelProtocol (old)             → channelProtocol
 *
 * mtPayload holds SWIFT blocks (block1, block2, block4Fields) plus
 * extracted flat financial fields (transactionReference, valueDate,
 * currency, interbankSettledAmount, etc.) and the rawFin string.
 */
@Data
@org.springframework.data.mongodb.core.mapping.Document(
        collection = "#{@appConfig.swiftCollection}"
)
public class SwiftMessage {
    @Id private String id;

    // Full nested SWIFT payload — blocks 1-5 + extracted flat fields
    @Field("mtPayload") private Document mtPayload;
}