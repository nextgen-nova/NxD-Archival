package com.swift.platform.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Search result row — fully mapped from the flat "messages" collection.
 * All 65 top-level DB fields + mtPayload nested fields covered.
 *
 * DB field → Java field mapping:
 *   Top-level:
 *     finSequenceNumber        → sequenceNumber (Integer)
 *     finSessionNumber         → sessionNumber
 *     messageFamily            → messageType  (UI alias: format)
 *     messageTypeCode          → messageCode  (UI alias: type)
 *     messageFormat            → messageFormat
 *     messageTypeDescription   → messageTypeDescription
 *     currentStatus            → status
 *     statusPhase              → phase
 *     statusAction             → action
 *     statusReason             → reason
 *     statusMessage            → statusMessage
 *     statusChangeSource       → statusChangeSource
 *     statusDecision           → statusDecision
 *     direction                → io  (UI alias: direction)
 *     dateCreated              → creationDate
 *     dateReceived             → receivedDT
 *     statusDate               → statusDate
 *     ampValueDate             → valueDate
 *     senderAddress            → sender
 *     receiverAddress          → receiver
 *     senderName               → senderInstitutionName
 *     receiverName             → receiverInstitutionName
 *     messageReference         → reference
 *     transactionReference     → transactionReference
 *     ampAmount (String)       → amount (Double)
 *     ampCurrency              → ccy  (UI alias: currency)
 *     ampDetailsOfCharges      → detailsOfCharges
 *     ampRemittanceInformation → remittanceInfo
 *     protocol                 → networkProtocol  (UI alias: network)
 *     networkChannel           → networkChannel
 *     networkPriority          → networkPriority
 *     communicationType        → deliveryMode + communicationType
 *     service                  → service
 *     backendChannel           → backendChannel
 *     backendChannelCode       → backendChannelCode
 *     backendChannelDescription→ backendChannelDescription
 *     channelCode              → channelCode
 *     channelProtocol          → backendChannelProtocol
 *     owner                    → owner  (UI alias: ownerUnit)
 *     workflow                 → workflow
 *     workflowModel            → workflowModel
 *     processingType           → processingType
 *     processPriority          → processPriority
 *     profileCode              → profileCode
 *     originatorApplication    → originatorApplication
 *     pdeIndication (String)   → pdeIndication + possibleDuplicate (Boolean)
 *     bulkType                 → bulkType
 *     bulkSequenceNumber       → bulkSequenceNumber
 *     bulkTotalMessages        → bulkTotalMessages
 *     finAppId                 → applicationId
 *     finServiceId             → serviceId
 *     finLogicalTerminal       → logicalTerminalAddress
 *     finMessagePriority       → messagePriority
 *     finDirectionId           → finDirectionId
 *     finMessageType           → finMessageType
 *     finReceiversAddress      → finReceiversAddress
 *     digestMCheckResult       → digestMCheckResult
 *     digest2CheckResult       → digest2CheckResult
 *     historyLines []          → historyLines (List<Map>)
 *                                 keys: index, historyDate, phase, action, reason,
 *                                       entity, channel, user, comment
 *
 *   mtPayload (nested):
 *     mtPayload.transactionReference    → mur
 *     mtPayload.bankOperationCode       → bankOperationCode
 *     mtPayload.detailsOfCharges        → (fallback for detailsOfCharges)
 *     mtPayload.remittanceInfo          → (fallback for remittanceInfo)
 *     mtPayload.currency                → payloadCurrency
 *     mtPayload.valueDate               → payloadValueDate
 *     mtPayload.interbankSettledAmount  → interbankSettledAmount
 *     mtPayload.instructedCurrency      → instructedCurrency
 *     mtPayload.instructedAmount        → instructedAmount
 *     mtPayload.orderingCustomer        → orderingCustomer
 *     mtPayload.orderingInstitution     → orderingInstitution
 *     mtPayload.senderCorrespondent     → senderCorrespondent
 *     mtPayload.accountWithInstitution  → accountWithInstitution
 *     mtPayload.beneficiaryCustomer     → beneficiaryCustomer
 *     mtPayload.rawFin                  → rawFin
 *     mtPayload.fieldCount              → payloadFieldCount
 *     mtPayload.payloadSize             → payloadSize
 *     mtPayload.block1.*                → applicationId/serviceId/logicalTerminalAddress/sessionNumber/sequenceNumber
 *     mtPayload.block2.*                → messagePriority/finDirectionId/finMessageType/finReceiversAddress
 *     mtPayload.block4Fields []         → block4Fields
 */
@Data
public class SearchResponse {

    // ── Core identity ─────────────────────────────────────────────────────
    private String  id;
    private Integer sequenceNumber;             // finSequenceNumber
    private String  sessionNumber;              // finSessionNumber

    // ── Classification ────────────────────────────────────────────────────
    private String  messageType;                // messageFamily (MT/MX)
    private String  messageCode;                // messageTypeCode (MT103…)
    private String  messageFormat;              // messageFormat
    private String  messageTypeDescription;     // messageTypeDescription

    // ── Status / lifecycle ────────────────────────────────────────────────
    private String  status;                     // currentStatus
    private String  phase;                      // statusPhase
    private String  action;                     // statusAction
    private String  reason;                     // statusReason
    private String  statusMessage;
    private String  statusChangeSource;
    private String  statusDecision;
    private String  io;                         // direction

    // ── Dates ─────────────────────────────────────────────────────────────
    private String  creationDate;               // dateCreated
    private String  receivedDT;                 // dateReceived
    private String  statusDate;
    private String  valueDate;                  // ampValueDate
    private String  deliveredDate;              // not in schema — null

    // ── Parties ───────────────────────────────────────────────────────────
    private String  sender;                     // senderAddress
    private String  receiver;                   // receiverAddress
    private String  correspondent;              // not flat — null
    private String  senderInstitutionName;      // senderName
    private String  receiverInstitutionName;    // receiverName

    // ── References ────────────────────────────────────────────────────────
    private String  reference;                  // messageReference
    private String  transactionReference;       // transactionReference
    private String  transferReference;          // not in schema — null
    private String  relatedReference;           // not in schema — null
    private String  mur;                        // mtPayload.transactionReference (tag 20)
    private String  uetr;                       // not in schema — null
    private String  userReference;              // not in schema — null
    private String  mxInputReference;           // not in schema — null
    private String  mxOutputReference;          // not in schema — null
    private String  networkReference;           // not in schema — null
    private String  e2eMessageId;               // not in schema — null

    // ── Financial ─────────────────────────────────────────────────────────
    private Double  amount;                     // ampAmount (parsed)
    private String  ccy;                        // ampCurrency
    private String  settlementDate;             // not in schema — null
    private String  detailsOfCharges;           // ampDetailsOfCharges / mtPayload.detailsOfCharges
    private String  remittanceInfo;             // ampRemittanceInformation / mtPayload.remittanceInfo
    private String  bankOperationCode;          // mtPayload.bankOperationCode
    private String  payloadCurrency;            // mtPayload.currency
    private String  payloadValueDate;           // mtPayload.valueDate (raw tag value e.g. "151212")
    private String  interbankSettledAmount;     // mtPayload.interbankSettledAmount
    private String  instructedCurrency;         // mtPayload.instructedCurrency
    private String  instructedAmount;           // mtPayload.instructedAmount

    // ── SWIFT party fields from mtPayload ─────────────────────────────────
    private String  orderingCustomer;           // mtPayload.orderingCustomer  (tag 50F)
    private String  orderingInstitution;        // mtPayload.orderingInstitution (tag 52A)
    private String  senderCorrespondent;        // mtPayload.senderCorrespondent (tag 53B)
    private String  accountWithInstitution;     // mtPayload.accountWithInstitution (tag 57C)
    private String  beneficiaryCustomer;        // mtPayload.beneficiaryCustomer (tag 59)

    // ── Network / routing ────────────────────────────────────────────────
    private String  networkProtocol;            // protocol
    private String  networkChannel;             // networkChannel
    private String  networkPriority;            // networkPriority
    private String  networkStatus;              // not in schema — null
    private String  deliveryMode;               // communicationType
    private String  service;
    private String  source;                     // not in schema — null
    private String  backendChannel;             // backendChannel
    private String  backendChannelCode;         // backendChannelCode
    private String  backendChannelDescription;  // backendChannelDescription
    private String  channelCode;                // channelCode
    private String  communicationType;          // communicationType
    private String  backendChannelProtocol;     // channelProtocol

    // ── Processing / ownership ────────────────────────────────────────────
    private String  owner;
    private String  workflow;
    private String  workflowModel;
    private String  processingType;
    private String  processPriority;
    private String  profileCode;
    private String  originatorApplication;
    private String  environment;                // not in schema — null

    // ── FIN header fields ─────────────────────────────────────────────────
    private String  applicationId;             // finAppId / block1.applicationId
    private String  serviceId;                 // finServiceId / block1.serviceId
    private String  logicalTerminalAddress;    // finLogicalTerminal / block1.logicalTerminalAddress
    private String  messagePriority;           // finMessagePriority / block2.messagePriority
    private String  finDirectionId;            // finDirectionId / block2.directionId
    private String  finMessageType;            // finMessageType / block2.messageType
    private String  finReceiversAddress;       // finReceiversAddress / block2.receiverAddress

    // ── Digest / integrity ────────────────────────────────────────────────
    private String  digestMCheckResult;        // digestMCheckResult
    private String  digest2CheckResult;        // digest2CheckResult

    // ── Bulk info ─────────────────────────────────────────────────────────
    private String  bulkType;
    private Integer bulkSequenceNumber;
    private Integer bulkTotalMessages;

    // ── Compliance / flags ────────────────────────────────────────────────
    private String  amlStatus;                 // not in schema — null
    private String  amlDetails;               // not in schema — null
    private String  finCopyService;           // not in schema — null
    private String  copyIndicator;            // not in schema — null
    private String  nack;                     // not in schema — null
    private Boolean possibleDuplicate;        // parsed from pdeIndication
    private Boolean crossBorder;              // not in schema — null
    private String  nrIndicator;              // not in schema — null
    private String  pdeIndication;            // raw "true"/"false"

    // ── Geography ────────────────────────────────────────────────────────
    private String  country;                  // not in schema — null
    private String  originCountry;            // not in schema — null
    private String  destinationCountry;       // not in schema — null
    private String  cityName;                 // not in schema — null

    // ── Payload metadata ─────────────────────────────────────────────────
    private Integer payloadFieldCount;         // mtPayload.fieldCount
    private String  payloadSize;               // mtPayload.payloadSize

    // ── History lines (top-level array) ──────────────────────────────────
    // Keys per entry: index, historyDate, phase, action, reason, entity, channel, user, comment
    private List<Map<String, Object>> historyLines;

    // ── FIN payload ───────────────────────────────────────────────────────
    private String  rawFin;                    // mtPayload.rawFin
    private List<Map<String, Object>> finHeaderFields;
    private List<Map<String, Object>> block4Fields; // mtPayload.block4Fields
    private List<Map<String, Object>> mxExtendedFields;
    private Map<String, String> mxNodeLabels;

    // ── Full raw document (for detail modal JSON view) ────────────────────
    private Map<String, Object> rawMessage;

    // ── UI convenience aliases ────────────────────────────────────────────
    private String  format;       // = messageType
    private String  type;         // = messageCode
    private String  date;         // dateOnly(creationDate)
    private String  time;         // timeOnly(creationDate)
    private String  direction;    // = io
    private String  network;      // = networkProtocol
    private String  sourceSystem; // null — no source field
    private String  ownerUnit;    // = owner
    private String  currency;     // = ccy
    private String  finCopy;      // = finCopyService (null)
}
