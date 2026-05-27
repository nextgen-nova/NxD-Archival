import React, { useEffect, useState } from "react";

const API_BASE = process.env.REACT_APP_API_BASE_URL || "http://localhost:8080";

const CATEGORY_OPTIONS = {
  custom: {
    label: "Custom Reports",
    reports: [
      { code: "MRM001", label: "MRM001 - MEPS+ Monthly Transaction Total", filters: ["department", "messageFormat"] },
      { code: "MRM002", label: "MRM002 - Monthly Transaction Total of All Departments", filters: ["department", "messageFormat"] },
      { code: "SWD003", label: "SWD003 - Details of Incoming Messages", filters: ["bankName", "messageFormat"] },
      { code: "SWD004", label: "SWD004 - Details of Outgoing Messages", filters: ["bankName", "messageFormat"] },
      { code: "SWS950", label: "SWS950 - Details of MT950 Messages - Closing Balance", filters: ["bankName", "messageFormat"] },
      { code: "SWM002", label: "SWM002 - Monthly Transaction Total of All Departments", filters: ["department", "messageFormat"] },
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

const FORMAT_OPTIONS = ["PDF", "Excel", "CSV"];
const PROFILE_OPTIONS = ["ACU", "ACT", "Operations", "Treasury"];
const MESSAGE_FORMAT_OPTIONS = ["All - MT & MX", "MT Only", "MX Only", "A1 - MT & MX"];
const FILTER_META = {
  bankName: { label: "Bank Name", placeholder: "Enter Name of the Bank" },
  department: { label: "Department", placeholder: "Enter Department" },
  receiver: { label: "Receiver", placeholder: "Enter Receiver" },
  sender: { label: "Sender", placeholder: "Enter Sender" },
  messageType: { label: "Message Type", placeholder: "Enter Message Type" },
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
  if (!value) return "—";
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
    downloadFormat: "PDF",
  };
}

function createSeedTemplates() {
  const now = new Date().toISOString();
  return [
    {
      id: "tpl-1",
      criteriaName: "SWD003",
      createdBy: "A0016699",
      lastModifier: "A0016699",
      creationDate: now,
      profile: "ACU",
      format: "PDF",
      criteria: {
        ...buildDefaultCriteria(),
        category: "custom",
        reportCode: "SWD003",
        bankName: "HBACED",
        messageFormat: "All - MT & MX",
      },
    },
  ];
}

function createSeedHistory() {
  const baseCustom = {
    ...buildDefaultCriteria(),
    category: "custom",
    reportCode: "SWD003",
    bankName: "HBACED",
    messageFormat: "All - MT & MX",
  };
  const baseTraffic = {
    ...buildDefaultCriteria(),
    category: "traffic",
    reportCode: "UTR001",
    receiver: "HBACED",
    sender: "A0016699",
    messageType: "User Traffic",
    messageFormat: "All - MT & MX",
    downloadFormat: "CSV",
  };
  return [
    { id: "hist-1", fileName: "Online_Generate_SWD003_A0016699_20251015_175219.pdf", status: "Generated", generationTime: "2025-10-15T17:52:19", format: "PDF", criteria: baseCustom },
    { id: "hist-2", fileName: "Online_Generate_MRM001_A0016699_20251015_184245.xlsx", status: "Generated", generationTime: "2025-10-15T18:42:45", format: "Excel", criteria: { ...buildDefaultCriteria(), category: "custom", reportCode: "MRM002", department: "ACT", messageFormat: "All - MT & MX", downloadFormat: "Excel" } },
    { id: "hist-3", fileName: "Online_Generate_UserTrafficReport_A0016699_20251014_132548.csv", status: "Generated", generationTime: "2025-10-14T13:25:48", format: "CSV", criteria: baseTraffic },
    { id: "hist-4", fileName: "Online_Generate_PossibleDuplicateMsg_A0016699_20251013_194010.pdf", status: "Failed", generationTime: "2025-10-13T19:40:10", format: "PDF", criteria: { ...buildDefaultCriteria(), category: "traffic", reportCode: "DUPMSG", application: "ALL - MT & MX", software: "Any" } },
  ];
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

function buildReportContent(template) {
  const reportName = getReportLabel(template.category, template.reportCode);
  const lines = [
    `Report Category: ${CATEGORY_OPTIONS[template.category]?.label || ""}`,
    `Report Type: ${reportName}`,
    `Start Date: ${prettyDate(template.startDate)}`,
    `End Date: ${prettyDate(template.endDate)}`,
    `Format: ${template.downloadFormat}`,
  ];

  Object.keys(FILTER_META).forEach((key) => {
    if (template[key]) lines.push(`${FILTER_META[key].label}: ${template[key]}`);
  });

  lines.push("");
  lines.push("Generated from SWIFT Platform Report Manager");
  return lines.join("\n");
}

function makeDownloadBlob(report, format) {
  const normalized = format.toLowerCase();
  const content = buildReportContent(report);
  if (normalized === "csv") {
    const csv = content
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const parts = line.split(": ");
        return parts.length > 1 ? `"${parts[0]}","${parts.slice(1).join(": ")}"` : `"${line}"`;
      })
      .join("\n");
    return { blob: new Blob([csv], { type: "text/csv;charset=utf-8;" }), ext: "csv" };
  }
  if (normalized === "excel") {
    const html = `
      <table border="1">
        ${content.split("\n").filter(Boolean).map((line) => {
          const parts = line.split(": ");
          return parts.length > 1
            ? `<tr><td>${parts[0]}</td><td>${parts.slice(1).join(": ")}</td></tr>`
            : `<tr><td colspan="2">${line}</td></tr>`;
        }).join("")}
      </table>`;
    return { blob: new Blob([html], { type: "application/vnd.ms-excel" }), ext: "xls" };
  }
  const pdfLike = `%PDF-REPORT\n${content}\n`;
  return { blob: new Blob([pdfLike], { type: "application/pdf" }), ext: "pdf" };
}

function triggerFileDownload(report, format) {
  const source = report.criteria
    ? { ...report.criteria, fileName: report.fileName }
    : report;
  const { blob, ext } = makeDownloadBlob(source, format);
  const anchor = document.createElement("a");
  anchor.href = URL.createObjectURL(blob);
  anchor.download = `${(report.fileName || source.fileName || "report").replace(/\.[^.]+$/, "")}.${ext}`;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(anchor.href);
}

function getReportLabel(category, reportCode) {
  const entry = CATEGORY_OPTIONS[category]?.reports.find((report) => report.code === reportCode);
  return entry?.label || reportCode;
}

function getCurrentReport(category, reportCode) {
  return CATEGORY_OPTIONS[category]?.reports.find((report) => report.code === reportCode)
    || CATEGORY_OPTIONS.custom.reports[0];
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
          <button type="button" onClick={onClose} aria-label="Close">×</button>
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
  const [templates, setTemplates] = useState([]);
  const [historyPage, setHistoryPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 1,
    pageNumber: 0,
    pageSize: 10,
    first: true,
    last: true,
    generatedCount: 0,
    inProgressCount: 0,
    failedCount: 0,
  });
  const [rowsPerPage, setRowsPerPage] = useState(10);
  const [page, setPage] = useState(1);
  const [collapsed, setCollapsed] = useState(false);
  const [toast, setToast] = useState(null);
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [loadingTemplates, setLoadingTemplates] = useState(false);

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

  async function loadHistory(targetPage = page, targetSize = rowsPerPage) {
    setLoadingHistory(true);
    try {
      const response = await apiFetch(`/api/reports/history?page=${Math.max(targetPage - 1, 0)}&size=${targetSize}`);
      const data = response?.data;
      if (data) {
        setHistoryPage({
          ...data,
          totalPages: Math.max(data.totalPages || 1, 1),
        });
      }
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to load download history." });
    } finally {
      setLoadingHistory(false);
    }
  }

  useEffect(() => {
    loadTemplates();
  }, []);

  useEffect(() => {
    loadHistory(page, rowsPerPage);
  }, [page, rowsPerPage]);

  const currentCategory = CATEGORY_OPTIONS[criteria.category] || CATEGORY_OPTIONS.custom;
  const currentReport = getCurrentReport(criteria.category, criteria.reportCode);
  const visibleFilters = currentReport.filters || [];

  const pagedHistory = historyPage.content || [];
  const totalPages = Math.max(historyPage.totalPages || 1, 1);
  const totalHistory = historyPage.totalElements || 0;
  const fromRecord = totalHistory === 0 ? 0 : (page - 1) * rowsPerPage + 1;
  const toRecord = Math.min(page * rowsPerPage, totalHistory);
  const generatedCount = historyPage.generatedCount || 0;
  const inProgressCount = historyPage.inProgressCount || 0;
  const failedCount = historyPage.failedCount || 0;

  function updateCriteria(key, value) {
    setCriteria((prev) => {
      if (key === "category") {
        const nextCategory = CATEGORY_OPTIONS[value];
        const nextReport = nextCategory.reports[0];
        return {
          ...prev,
          category: value,
          reportCode: nextReport.code,
        };
      }
      return { ...prev, [key]: value };
    });
  }

  function resetCriteria() {
    setCriteria(buildDefaultCriteria());
    setToast({ type: "success", text: "Report criteria reset." });
  }

  async function runRefresh() {
    try {
      const response = await apiFetch(`/api/reports/history/refresh?page=${Math.max(page - 1, 0)}&size=${rowsPerPage}`, {
        method: "POST",
      });
      if (response?.data) {
        setHistoryPage({
          ...response.data,
          totalPages: Math.max(response.data.totalPages || 1, 1),
        });
      }
      setToast({ type: "success", text: "Report status refreshed." });
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to refresh report status." });
    }
  }

  async function enqueueHistoryItem(nextCriteria, shouldAutoDownload = true) {
    const error = validateRange(nextCriteria.startDate, nextCriteria.endDate);
    if (error) {
      setToast({ type: "error", text: error });
      return false;
    }

    try {
      const response = await apiFetch("/api/reports/generate", {
        method: "POST",
        body: JSON.stringify({
          format: nextCriteria.downloadFormat,
          criteria: nextCriteria,
        }),
      });
      const entry = response?.data;
      await loadHistory(1, rowsPerPage);
      setPage(1);
      setToast({ type: "success", text: `${getReportLabel(nextCriteria.category, nextCriteria.reportCode)} generated.` });
      if (shouldAutoDownload && entry?.id) {
        await downloadHistoryItem(entry);
      }
      return true;
    } catch (fetchError) {
      setToast({ type: "error", text: fetchError.message || "Unable to generate report." });
      return false;
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
      const ok = await enqueueHistoryItem({ ...template.criteria, downloadFormat: template.format }, false);
      if (ok) {
        setToast({ type: "success", text: `${template.criteriaName} applied and generation started.` });
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

  async function downloadHistoryItem(item) {
    if (item.status !== "Generated" && item.status !== "GENERATED") return;
    try {
      const token = localStorage.getItem("token");
      const response = await fetch(`${API_BASE}/api/reports/history/${item.id}/download`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!response.ok) {
        throw new Error("Unable to download report.");
      }
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = item.fileName;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      window.URL.revokeObjectURL(url);
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to download report." });
    }
  }

  async function deleteHistoryItem(itemId) {
    try {
      await apiFetch(`/api/reports/history/${itemId}`, { method: "DELETE" });
      await loadHistory(page, rowsPerPage);
      setToast({ type: "success", text: "History record removed." });
    } catch (error) {
      setToast({ type: "error", text: error.message || "Unable to remove history record." });
    }
  }

  function handleGenerate() {
    enqueueHistoryItem(criteria, true);
  }

  return (
    <div className="reports-page">
      <div className="reports-shell">
        {toast && <div className={`reports-toast ${toast.type}`}>{toast.text}</div>}

        <section className="reports-card">
          <div className="reports-card-head">
            <div>
              <h1 className="reports-title">Report Manager</h1>
            </div>
            <div className="reports-card-actions">
              <div className="reports-summary">
                <div className="reports-stat">
                  <span className="reports-stat-label">Generated</span>
                  <span className="reports-stat-value">{generatedCount}</span>
                </div>
                <div className="reports-stat">
                  <span className="reports-stat-label">In Progress</span>
                  <span className="reports-stat-value">{inProgressCount}</span>
                </div>
                <div className="reports-stat">
                  <span className="reports-stat-label">Saved Criteria</span>
                  <span className="reports-stat-value">{templates.length}</span>
                </div>
              </div>
              <span className="reports-pill"><strong>{currentCategory.label}</strong></span>
              <button type="button" className="reports-collapse" onClick={() => setCollapsed((prev) => !prev)}>
                {collapsed ? "Expand" : "Collapse"}
              </button>
            </div>
          </div>

          {!collapsed && (
            <div className="reports-form-wrap">
              <div className="reports-form-grid">
                <div className="report-field">
                  <label>Report Category</label>
                  <select className="report-select" value={criteria.category} onChange={(event) => updateCriteria("category", event.target.value)}>
                    {Object.entries(CATEGORY_OPTIONS).map(([value, option]) => (
                      <option key={value} value={value}>{option.label}</option>
                    ))}
                  </select>
                </div>

                <div className="report-field">
                  <label>Report List</label>
                  <select className="report-select" value={criteria.reportCode} onChange={(event) => updateCriteria("reportCode", event.target.value)}>
                    {currentCategory.reports.map((report) => (
                      <option key={report.code} value={report.code}>{report.label}</option>
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

                <div className="report-field">
                  <label>Download Format</label>
                  <div className="report-inline-radio">
                    {FORMAT_OPTIONS.map((format) => (
                      <label key={format} className="report-radio">
                        <input
                          type="radio"
                          name="downloadFormat"
                          checked={criteria.downloadFormat === format}
                          onChange={() => updateCriteria("downloadFormat", format)}
                        />
                        <span>{format}</span>
                      </label>
                    ))}
                  </div>
                </div>

                {visibleFilters.map((field) => {
                  const meta = FILTER_META[field];
                  if (!meta) return null;
                  return (
                    <div className="report-field" key={field}>
                      <label>{meta.label}</label>
                      {meta.type === "select" ? (
                        <select className="report-select" value={criteria[field] || meta.options[0]} onChange={(event) => updateCriteria(field, event.target.value)}>
                          {meta.options.map((option) => (
                            <option key={option} value={option}>{option}</option>
                          ))}
                        </select>
                      ) : (
                        <input
                          className="report-input"
                          value={criteria[field] || ""}
                          onChange={(event) => updateCriteria(field, event.target.value)}
                          placeholder={meta.placeholder}
                        />
                      )}
                    </div>
                  );
                })}

              </div>

              <div className="reports-toolbar">
                <div className="reports-toolbar-left">
                  <button type="button" className="reports-button primary" onClick={handleGenerate}>Download</button>
                  <span className="reports-pill"><strong>{criteria.downloadFormat}</strong> selected output</span>
                </div>
                <div className="reports-toolbar-right">
                  <button type="button" className="reports-button secondary" onClick={() => setSaveModalOpen(true)}>Save</button>
                  <button type="button" className="reports-button ghost" onClick={resetCriteria}>Clear</button>
                  <button type="button" className="reports-button ghost" onClick={runRefresh}>Refresh</button>
                </div>
              </div>
            </div>
          )}
        </section>

        <section className="reports-card">
          <div className="reports-table-wrap">
            <h3 className="reports-section-title">Saved Criteria</h3>
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
          </div>
        </section>

        <section className="reports-card">
          <div className="reports-table-wrap">
            <h3 className="reports-section-title">Download History</h3>
                {loadingHistory ? (
              <div className="reports-empty">Loading report history…</div>
            ) : totalHistory === 0 ? (
              <div className="reports-empty">No reports have been generated yet.</div>
            ) : (
              <>
                <table className="reports-table">
                  <thead>
                    <tr>
                      <th>File Name</th>
                      <th>Status</th>
                      <th>Generation Time</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pagedHistory.map((item) => (
                      <tr key={item.id}>
                        <td>{item.fileName}</td>
                        <td>
                          <span className={`reports-status ${item.status.toLowerCase().replace(" ", "-")}`}>{item.status}</span>
                        </td>
                        <td>{prettyDate(item.generationTime)}</td>
                        <td>
                          <div className="reports-mini-actions">
                            <button
                              type="button"
                              className="reports-icon-btn"
                              onClick={() => downloadHistoryItem(item)}
                              disabled={item.status !== "Generated"}
                              title="Download"
                            >
                              ↓
                            </button>
                            <button
                              type="button"
                              className="reports-icon-btn danger"
                              onClick={() => deleteHistoryItem(item.id)}
                              title="Delete"
                            >
                              🗑
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div className="reports-history-foot">
                  <span className="reports-range">{fromRecord}-{toRecord} of {history.length}</span>
                  <div className="reports-pagination">
                    <label>Rows</label>
                    <select value={rowsPerPage} onChange={(event) => { setRowsPerPage(Number(event.target.value)); setPage(1); }}>
                      {[10, 20, 30].map((size) => <option key={size} value={size}>{size}</option>)}
                    </select>
                    <button type="button" className="reports-button ghost" disabled={page === 1} onClick={() => setPage((prev) => Math.max(1, prev - 1))}>‹</button>
                    <span className="reports-range">Page {page} / {totalPages}</span>
                    <button type="button" className="reports-button ghost" disabled={page === totalPages} onClick={() => setPage((prev) => Math.min(totalPages, prev + 1))}>›</button>
                  </div>
                </div>
              </>
            )}
          </div>
        </section>

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
