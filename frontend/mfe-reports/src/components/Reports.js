import React, { useEffect, useState } from "react";

const API_BASE = process.env.REACT_APP_API_BASE_URL || "http://localhost:8080";

const CATEGORY_OPTIONS = {
  custom: {
    label: "Custom Reports",
    reports: [
      { code: "MRM001", label: "MRM001 - MEPS+ Monthly Transaction Total", filters: ["department", "messageFormat"] },
      { code: "MRM002", label: "MRM002 - Monthly Transaction Total of All Departments", filters: ["department", "messageFormat", "messageType", "currency", "status", "direction", "sender", "receiver", "branch"] },
      { code: "SWD003", label: "SWD003 - Details of Incoming Messages", filters: ["bankName", "messageFormat"] },
      { code: "SWD004", label: "SWD004 - Details of Outgoing Messages", filters: ["bankName", "messageFormat"] },
      { code: "SWS950", label: "SWS950 - Details of MT950 Messages - Closing Balance", filters: ["bankName", "messageFormat"] },
      { code: "SWM002", label: "SWM002 - Monthly Transaction Total of All Departments", filters: ["department", "messageFormat", "messageType", "currency", "status", "direction", "sender", "receiver", "branch"] },
    ],
  },
  traffic: {
    label: "Traffic Reports",
    reports: [
      { code: "UTR001", label: "User Traffic Report", filters: ["receiver", "sender", "messageType", "messageFormat"] },
      { code: "ISN001", label: "ISN Gaps", filters: ["receiver", "messageType", "messageFormat"] },
      { code: "MSGNACK", label: "Messages NACKED", filters: ["receiver", "sender", "messageType", "messageFormat"] },
      { code: "OSN001", label: "OSN Gaps", filters: ["receiver", "sender", "messageType", "messageFormat"] },
      { code: "FINMSG", label: "Financial Messages", filters: ["receiver", "sender", "messageType", "messageFormat"] },
      { code: "DBEXT", label: "DB Extract", filters: ["application", "software"] },
      { code: "DUPMSG", label: "Possible Duplicate Messages", filters: ["application", "software"] },
    ],
  },
  idm: {
    label: "IDM Reports",
    reports: [
      { code: "IDM001", label: "Dormant ID Report", filters: ["application", "software"] },
      { code: "IDM002", label: "Logon Validation Report", filters: ["application", "software"] },
      { code: "IDM003", label: "ID Listing Report", filters: ["application", "software"] },
      { code: "IDM004", label: "Login Failure Report", filters: ["application", "software"] },
    ],
  },
};

const CATEGORY_LABELS = {
  custom: "Custom Reports",
  traffic: "Traffic Reports",
  idm: "IDM Reports",
};

const FORMAT_OPTIONS = ["PDF", "Excel", "CSV"];
const PROFILE_OPTIONS = ["ACU", "ACT", "Operations", "Treasury"];
const MESSAGE_FORMAT_OPTIONS = ["All - MT & MX", "MT Only", "MX Only", "A1 - MT & MX"];
const DIRECTION_OPTIONS = ["All", "Inbound", "Outbound"];
const STATUS_OPTIONS = ["Any", "Success", "Failed", "Pending", "Repaired", "Rejected", "Cancelled"];
const FILTER_META = {
  bankName: { label: "Bank Name", placeholder: "Enter Name of the Bank" },
  department: { label: "Department", placeholder: "Enter Department" },
  receiver: { label: "Receiver", placeholder: "Enter Receiver" },
  sender: { label: "Sender", placeholder: "Enter Sender" },
  messageType: { label: "Message Type", placeholder: "Enter Message Type" },
  currency: { label: "Currency", placeholder: "Enter Currency" },
  status: { label: "Status", type: "select", options: STATUS_OPTIONS },
  direction: { label: "Direction", type: "select", options: DIRECTION_OPTIONS },
  branch: { label: "Branch", placeholder: "Enter Branch" },
  messageFormat: { label: "Message Format", type: "select", options: MESSAGE_FORMAT_OPTIONS },
  application: { label: "Application", type: "select", options: ["ALL - MT & MX", "MEPS+", "SWIFT", "IDM"] },
  software: { label: "Software", type: "select", options: ["Any", "AWH", "SAA", "IDM Portal"] },
};

const CRITERIA_KEY = "reports_manager_lastcriteria_v1";

async function apiFetch(path, options = {}) {
  const token = localStorage.getItem("token");
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });

  if (!response.ok) {
    let message = "Request failed.";
    try {
      const body = await response.json();
      message = body?.message || body?.error || response.statusText || message;
    } catch {
      message = response.statusText || message;
    }
    throw new Error(message);
  }

  if (response.status === 204) return null;
  return response.json();
}

function pad(value) {
  return String(value).padStart(2, "0");
}

function toDateTimeLocal(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function prettyDate(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function buildDefaultCriteria() {
  const now = new Date();
  const start = new Date(now);
  start.setDate(start.getDate() - 7);
  return {
    category: "custom",
    reportCode: CATEGORY_OPTIONS.custom.reports[0].code,
    startDate: toDateTimeLocal(start),
    endDate: toDateTimeLocal(now),
    bankName: "",
    department: "",
    receiver: "",
    sender: "",
    messageType: "",
    messageFormat: "All - MT & MX",
    application: "ALL - MT & MX",
    software: "Any",
    currency: "",
    status: "Any",
    direction: "All",
    branch: "",
    downloadFormat: "PDF",
  };
}

function categoryKey(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (normalized.includes("traffic")) return "traffic";
  if (normalized.includes("idm")) return "idm";
  if (!normalized || normalized.includes("custom")) return "custom";
  return normalized.replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "custom";
}

function normalizeStaticFilter(filter) {
  const fallback = FILTER_META[filter] || {};
  return {
    name: filter,
    label: fallback.label || filter,
    type: fallback.type || (fallback.options?.length ? "select" : "text"),
    values: fallback.options || [],
  };
}

function normalizeStaticReport(report, category) {
  return {
    ...report,
    category,
    filters: (report.filters || []).map(normalizeStaticFilter),
  };
}

function cloneStaticCategories() {
  const grouped = {};
  Object.entries(CATEGORY_OPTIONS).forEach(([key, option]) => {
    grouped[key] = {
      label: option.label,
      reports: option.reports.map((report) => normalizeStaticReport(report, key)),
    };
  });
  return grouped;
}

function normalizeReportDefinition(definition) {
  const code = definition.reportCode || definition.code;
  const category = categoryKey(definition.category);
  return {
    code,
    label: definition.displayName || `${code} - ${definition.reportName || "Report"}`,
    category,
    categoryLabel: definition.category || CATEGORY_LABELS[category] || category,
    filters: Array.isArray(definition.filters) ? definition.filters : [],
  };
}

function mergedCategoryOptions(definitions) {
  const grouped = cloneStaticCategories();
  definitions.forEach((definition) => {
    const normalized = normalizeReportDefinition(definition);
    if (!normalized.code) return;
    grouped[normalized.category] = grouped[normalized.category] || {
      label: normalized.categoryLabel,
      reports: [],
    };
    grouped[normalized.category].label = normalized.categoryLabel || grouped[normalized.category].label;
    const existingIndex = grouped[normalized.category].reports.findIndex((report) => report.code === normalized.code);
    if (existingIndex >= 0) {
      grouped[normalized.category].reports[existingIndex] = {
        ...grouped[normalized.category].reports[existingIndex],
        ...normalized,
        filters: normalized.filters.length
          ? normalized.filters
          : grouped[normalized.category].reports[existingIndex].filters,
      };
    } else {
      grouped[normalized.category].reports.push(normalized);
    }
  });
  return grouped;
}

function filterDefinitionsForUi(report) {
  return (report?.filters || []).filter((filter) => !["fromDate", "toDate"].includes(filter.name));
}

function filterMeta(filter) {
  const fallback = FILTER_META[filter.name] || {};
  const values = filter.values?.length ? filter.values : fallback.options;
  return {
    name: filter.name,
    label: filter.label || fallback.label || filter.name,
    type: filter.type || fallback.type || (values?.length ? "select" : "text"),
    options: values,
    placeholder: fallback.placeholder || `Enter ${filter.label || filter.name}`,
  };
}

function readStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function writeStorage(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function getCurrentReport(categoryOptions, category, reportCode) {
  return categoryOptions[category]?.reports.find((report) => report.code === reportCode)
    || categoryOptions[category]?.reports[0]
    || Object.values(categoryOptions).flatMap((option) => option.reports || [])[0]
    || { code: reportCode || "", label: reportCode || "No report template", filters: [] };
}

function getReportLabel(categoryOptions, category, reportCode) {
  const entry = categoryOptions[category]?.reports.find((report) => report.code === reportCode);
  return entry?.label || reportCode;
}

function isMrm002Report(reportCode) {
  return reportCode === "MRM002" || reportCode === "SWM002";
}

function previewSummaryCards(preview) {
  const summary = preview?.sections?.find((section) => section.title === "Report Summary");
  if (!summary?.rows?.length) return [];
  const wanted = new Set([
    "Total Departments",
    "Total Transactions",
    "Total Successful Transactions",
    "Total Failed Transactions",
    "Grand Total Amount",
    "Currency",
  ]);
  return summary.rows
    .filter((row) => wanted.has(row?.[0]))
    .map((row) => ({ label: row[0], value: row[1] || "-" }));
}

function buildFileName(criteria) {
  const now = new Date();
  return `Online_Generate_${criteria.reportCode}_${criteria.createdBy || "A0016699"}_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.${criteria.downloadFormat.toLowerCase() === "excel" ? "xlsx" : criteria.downloadFormat.toLowerCase()}`;
}

function validateRange(startDate, endDate) {
  const start = new Date(startDate);
  const end = new Date(endDate);
  const diff = end.getTime() - start.getTime();
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return "Please provide a valid date range.";
  if (diff < 0) return "Start date must be earlier than end date.";
  if (diff > 31 * 24 * 60 * 60 * 1000) return "Date range cannot exceed 31 days.";
  return "";
}

function SaveCriteriaModal({ open, onClose, onSave, initialName }) {
  const [name, setName] = useState(initialName);

  useEffect(() => {
    if (open) setName(initialName);
  }, [open, initialName]);

  if (!open) return null;

  return (
    <div className="reports-modal-backdrop" onClick={onClose}>
      <div className="reports-modal" onClick={(event) => event.stopPropagation()}>
        <div className="reports-modal-head">
          <div>
            <h3>Save Report Criteria</h3>
            <p className="reports-modal-sub">Store the current filters as a reusable template for recurring report runs.</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close">x</button>
        </div>
        <div className="reports-modal-body">
          <div className="report-field">
            <label htmlFor="criteriaName">Criteria Name</label>
            <input
              id="criteriaName"
              className="report-input"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Enter Criteria Name"
            />
          </div>
        </div>
        <div className="reports-modal-foot">
          <button type="button" className="reports-button secondary" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="reports-button primary"
            onClick={() => onSave(name)}
            disabled={!name.trim()}
          >
            Save Criteria
          </button>
        </div>
      </div>
    </div>
  );
}

export default function Reports() {
  const [criteria, setCriteria] = useState(() => readStorage(CRITERIA_KEY, buildDefaultCriteria()));
  const [reportDefinitions, setReportDefinitions] = useState([]);
  const [loadingDefinitions, setLoadingDefinitions] = useState(false);
  const [definitionsLoaded, setDefinitionsLoaded] = useState(false);
  const [templates, setTemplates] = useState([]);
  const [collapsed, setCollapsed] = useState(false);
  const [savedCriteriaOpen, setSavedCriteriaOpen] = useState(false);
  const [toast, setToast] = useState(null);
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [loadingTemplates, setLoadingTemplates] = useState(false);
  const [preview, setPreview] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");

  useEffect(() => {
    writeStorage(CRITERIA_KEY, criteria);
  }, [criteria]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = window.setTimeout(() => setToast(null), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  async function loadTemplates() {
    setLoadingTemplates(true);
    try {
      const response = await apiFetch("/api/reports/templates");
      setTemplates(response?.data || []);
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to load saved criteria." });
    } finally {
      setLoadingTemplates(false);
    }
  }

  async function loadReportDefinitions() {
    setLoadingDefinitions(true);
    try {
      const response = await apiFetch("/api/reports/definitions");
      const definitions = response?.data || [];
      setReportDefinitions(definitions);
      const grouped = mergedCategoryOptions(definitions);
      setCriteria((prev) => {
        const currentExists = grouped[prev.category]?.reports.some((report) => report.code === prev.reportCode);
        if (currentExists) return prev;
        const firstCategoryKey = Object.entries(grouped).find(([, option]) => option.reports.length > 0)?.[0];
        if (!firstCategoryKey) return prev;
        const firstReport = grouped[firstCategoryKey].reports[0];
        return {
          ...prev,
          category: firstCategoryKey,
          reportCode: firstReport.code,
        };
      });
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to load report definitions." });
    } finally {
      setDefinitionsLoaded(true);
      setLoadingDefinitions(false);
    }
  }

  useEffect(() => {
    loadReportDefinitions();
    loadTemplates();
  }, []);

  const dynamicCategoryOptions = mergedCategoryOptions(definitionsLoaded ? reportDefinitions : []);
  const currentCategory = dynamicCategoryOptions[criteria.category] || dynamicCategoryOptions.custom || { label: "No Report Templates", reports: [] };
  const currentReport = getCurrentReport(dynamicCategoryOptions, criteria.category, criteria.reportCode);
  const visibleFilters = filterDefinitionsForUi(currentReport);
  const highlightedFilters = visibleFilters.slice(0, 2);
  const remainingFilters = visibleFilters.slice(2);

  function updateCriteria(key, value) {
    setPreview(null);
    setPreviewError("");
    setCriteria((prev) => {
      if (key === "category") {
        const nextCategory = dynamicCategoryOptions[value];
        const nextReport = nextCategory.reports[0];
        return {
          ...prev,
          category: value,
          reportCode: nextReport?.code || prev.reportCode,
        };
      }
      return { ...prev, [key]: value };
    });
  }

  function resetCriteria() {
    setCriteria(buildDefaultCriteria());
    setToast({ type: "success", text: "Report criteria reset." });
  }

  async function generateReport(nextCriteria) {
    const error = validateRange(nextCriteria.startDate, nextCriteria.endDate);
    if (error) {
      setToast({ type: "error", text: error });
      return false;
    }

    try {
      const token = localStorage.getItem("token");
      const response = await fetch(`${API_BASE}/api/reports/generate`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({
          format: nextCriteria.downloadFormat,
          criteria: nextCriteria,
        }),
      });
      if (!response.ok) {
        let message = "Unable to generate report.";
        try {
          const body = await response.json();
          message = body?.message || body?.error || response.statusText || message;
        } catch {
          message = response.statusText || message;
        }
        throw new Error(message);
      }
      const blob = await response.blob();
      const disposition = response.headers.get("Content-Disposition") || "";
      const fileNameMatch = disposition.match(/filename="([^"]+)"/i);
      const fileName = fileNameMatch?.[1] || buildFileName(nextCriteria);
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      window.URL.revokeObjectURL(url);
      setToast({ type: "success", text: `${getReportLabel(dynamicCategoryOptions, nextCriteria.category, nextCriteria.reportCode)} downloaded.` });
      return true;
    } catch (fetchError) {
      setToast({ type: "error", text: fetchError.message || "Unable to generate report." });
      return false;
    }
  }

  async function loadPreview() {
    const error = validateRange(criteria.startDate, criteria.endDate);
    if (error) {
      setPreviewError(error);
      setPreview(null);
      return;
    }
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const response = await apiFetch("/api/reports/preview", {
        method: "POST",
        body: JSON.stringify({
          format: criteria.downloadFormat,
          criteria,
        }),
      });
      setPreview(response?.data || null);
    } catch (error) {
      setPreview(null);
      setPreviewError(error.message || "Unable to load report preview.");
    } finally {
      setPreviewLoading(false);
    }
  }

  async function saveCriteria(name) {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      await apiFetch("/api/reports/templates", {
        method: "POST",
        body: JSON.stringify({
          criteriaName: trimmed,
          format: criteria.downloadFormat,
          profile: PROFILE_OPTIONS[templates.length % PROFILE_OPTIONS.length],
          criteria: { ...criteria },
        }),
      });
      await loadTemplates();
      setSaveModalOpen(false);
      setToast({ type: "success", text: `${trimmed} saved as reusable criteria.` });
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to save criteria." });
    }
  }

  async function runTemplate(template) {
    try {
      setCriteria(template.criteria);
      const ok = await generateReport({ ...template.criteria, downloadFormat: template.format });
      if (ok) {
        setToast({ type: "success", text: `${template.criteriaName} downloaded.` });
      }
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to run saved criteria." });
    }
  }

  async function deleteTemplate(templateId) {
    try {
      await apiFetch(`/api/reports/templates/${templateId}`, { method: "DELETE" });
      await loadTemplates();
      setToast({ type: "success", text: "Saved criteria removed." });
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to remove saved criteria." });
    }
  }

  async function updateTemplateFormat(templateId, format) {
    setTemplates((prev) => prev.map((item) => item.id === templateId ? { ...item, format } : item));
    try {
      await apiFetch(`/api/reports/templates/${templateId}/format`, {
        method: "POST",
        body: JSON.stringify({ format }),
      });
    } catch (error) {
      await loadTemplates();
      setToast({ type: "error", text: error.message || "Unable to update template format." });
    }
  }

  function handleGenerate() {
    generateReport(criteria);
  }

  return (
    <div className="reports-page">
      <div className="reports-shell">
        {toast && <div className={`reports-toast ${toast.type}`}>{toast.text}</div>}

        <section className="reports-card reports-manager-card">
          <div className="reports-card-head">
            <div className="reports-head-copy">
              <h1 className="reports-title">Report Manager</h1>
            </div>
            <div className="reports-card-actions">
              <button
                type="button"
                className={`reports-metric-card reports-metric-button ${savedCriteriaOpen ? "active" : ""}`}
                onClick={() => setSavedCriteriaOpen((prev) => !prev)}
              >
                <span className="reports-metric-label">Saved Criteria</span>
                <strong className="reports-metric-value">{templates.length}</strong>
              </button>
              <div className="reports-metric-card">
                <span className="reports-metric-label">Current Category</span>
                <strong className="reports-metric-value reports-metric-value-text">{currentCategory.label}</strong>
              </div>
              <button type="button" className="reports-collapse" onClick={() => setCollapsed((prev) => !prev)}>
                {collapsed ? "Expand" : "Collapse"}
              </button>
            </div>
          </div>

          {!collapsed && (
            <div className="reports-form-wrap">
              <div className="reports-workspace">
                <div className="reports-main-panel">
                  <div className="reports-panel">
                    <div className="reports-form-grid reports-grid-primary">
                      <div className="report-field">
                        <label>Report Category</label>
                        <select className="report-select" value={criteria.category} onChange={(event) => updateCriteria("category", event.target.value)}>
                          {Object.entries(dynamicCategoryOptions).filter(([, option]) => option.reports.length > 0).length === 0 && (
                            <option value={criteria.category}>{loadingDefinitions ? "Loading templates..." : "No report templates found"}</option>
                          )}
                          {Object.entries(dynamicCategoryOptions).filter(([, option]) => option.reports.length > 0).map(([value, option]) => (
                            <option key={value} value={value}>{option.label}</option>
                          ))}
                        </select>
                      </div>

                      <div className="report-field span-2">
                        <label>Report List</label>
                        <select className="report-select" value={criteria.reportCode} onChange={(event) => updateCriteria("reportCode", event.target.value)}>
                          {currentCategory.reports.length === 0 && (
                            <option value={criteria.reportCode}>{loadingDefinitions ? "Loading report list..." : "No template available"}</option>
                          )}
                          {currentCategory.reports.map((report) => (
                            <option key={report.code} value={report.code}>{report.label}</option>
                          ))}
                        </select>
                      </div>

                      <div className="report-field">
                        <label>Download Format</label>
                        <select className="report-select" value={criteria.downloadFormat} onChange={(event) => updateCriteria("downloadFormat", event.target.value)}>
                          {FORMAT_OPTIONS.map((format) => (
                            <option key={format} value={format}>{format}</option>
                          ))}
                        </select>
                      </div>

                      <div className="report-field">
                        <label>Starting Date</label>
                        <input className="report-input" type="datetime-local" value={criteria.startDate} onChange={(event) => updateCriteria("startDate", event.target.value)} />
                      </div>

                      <div className="report-field">
                        <label>Ending Date</label>
                        <input className="report-input" type="datetime-local" value={criteria.endDate} onChange={(event) => updateCriteria("endDate", event.target.value)} />
                      </div>

                      {highlightedFilters.map((filter) => {
                        const meta = filterMeta(filter);
                        if (!meta.name) return null;
                        return (
                          <div className="report-field" key={meta.name}>
                            <label>{meta.label}</label>
                            {meta.type === "select" && meta.options?.length ? (
                              <select className="report-select" value={criteria[meta.name] || meta.options[0]} onChange={(event) => updateCriteria(meta.name, event.target.value)}>
                                {meta.options.map((option) => (
                                  <option key={option} value={option}>{option}</option>
                                ))}
                              </select>
                            ) : (
                              <input
                                className="report-input"
                                value={criteria[meta.name] || ""}
                                onChange={(event) => updateCriteria(meta.name, event.target.value)}
                                placeholder={meta.placeholder}
                              />
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {remainingFilters.length > 0 && (
                    <div className="reports-panel">
                    <div className="reports-panel-head">
                      <div>
                        <h2 className="reports-panel-title">Additional Filters</h2>
                      </div>
                    </div>
                      <div className="reports-form-grid reports-grid-secondary">
                        {remainingFilters.map((filter) => {
                          const meta = filterMeta(filter);
                          if (!meta.name) return null;
                          return (
                            <div className="report-field" key={meta.name}>
                              <label>{meta.label}</label>
                              {meta.type === "select" && meta.options?.length ? (
                                <select className="report-select" value={criteria[meta.name] || meta.options[0]} onChange={(event) => updateCriteria(meta.name, event.target.value)}>
                                  {meta.options.map((option) => (
                                    <option key={option} value={option}>{option}</option>
                                  ))}
                                </select>
                              ) : (
                                <input
                                  className="report-input"
                                  value={criteria[meta.name] || ""}
                                  onChange={(event) => updateCriteria(meta.name, event.target.value)}
                                  placeholder={meta.placeholder}
                                />
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>

                <aside className="reports-side-panel">
                  <div className="reports-panel reports-side-card">
                    <div className="reports-panel-head">
                      <div>
                        <h2 className="reports-panel-title">Export</h2>
                      </div>
                    </div>

                    <div className="reports-side-summary">
                      <div className="reports-summary-row">
                        <span>Format</span>
                        <strong>{criteria.downloadFormat}</strong>
                      </div>
                      <div className="reports-summary-row">
                        <span>Code</span>
                        <strong>{criteria.reportCode}</strong>
                      </div>
                      <div className="reports-summary-row">
                        <span>Category</span>
                        <strong>{currentCategory.label}</strong>
                      </div>
                      <div className="reports-summary-row">
                        <span>Range</span>
                        <strong>{criteria.startDate?.slice(0, 10)} to {criteria.endDate?.slice(0, 10)}</strong>
                      </div>
                    </div>

                    <div className="reports-actions-stack">
                      <button type="button" className="reports-button primary reports-button-wide" onClick={handleGenerate}>Download Report</button>
                      <button type="button" className="reports-button secondary reports-button-wide" onClick={() => setSaveModalOpen(true)}>Save Criteria</button>
                      <button type="button" className="reports-button ghost reports-button-wide" onClick={resetCriteria}>Clear</button>
                    </div>
                  </div>
                </aside>
              </div>
            </div>
          )}
        </section>

        {savedCriteriaOpen && (
          <section className="reports-card reports-saved-panel">
            <div className="reports-table-wrap">
              <div className="reports-table-head">
                <div>
                  <h3 className="reports-section-title">Saved Criteria</h3>
                </div>
                <button type="button" className="reports-collapse reports-collapse-small" onClick={() => setSavedCriteriaOpen(false)}>
                  Close
                </button>
              </div>
              {loadingTemplates ? (
                <div className="reports-empty">Loading saved criteria...</div>
              ) : templates.length === 0 ? (
                <div className="reports-empty">No saved criteria yet.</div>
              ) : (
                <table className="reports-table">
                  <thead>
                    <tr>
                      <th>Criteria Name</th>
                      <th>Create By</th>
                      <th>Last Modifier</th>
                      <th>Creation Date</th>
                      <th>Profile</th>
                      <th>Format</th>
                      <th>Trigger</th>
                      <th>Delete</th>
                    </tr>
                  </thead>
                  <tbody>
                    {templates.map((template) => (
                      <tr key={template.id}>
                        <td>{template.criteriaName}</td>
                        <td>{template.createdBy}</td>
                        <td>{template.lastModifier}</td>
                        <td>{prettyDate(template.creationDate)}</td>
                        <td>{template.profile}</td>
                        <td>
                          <select className="report-select" value={template.format} onChange={(event) => updateTemplateFormat(template.id, event.target.value)}>
                            {FORMAT_OPTIONS.map((format) => <option key={format} value={format}>{format}</option>)}
                          </select>
                        </td>
                        <td><button type="button" className="reports-button primary" onClick={() => runTemplate(template)}>Run</button></td>
                        <td><button type="button" className="reports-button danger" onClick={() => deleteTemplate(template.id)}>Delete</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </section>
        )}

        <SaveCriteriaModal
          open={saveModalOpen}
          onClose={() => setSaveModalOpen(false)}
          onSave={saveCriteria}
          initialName={criteria.reportCode}
        />
      </div>
    </div>
  );
}
