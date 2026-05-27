import React, { useCallback, useEffect, useMemo, useState } from "react";
import "./RawCopies.css";
import "./Failures.css";
import { useAuth } from "../AuthContext";

const API_BASE = `${process.env.REACT_APP_API_BASE_URL}/api/failures`;

const fmtDate = (value) => {
    if (!value) return "—";
    try {
        return new Date(value).toLocaleString("en-US", {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false,
        });
    } catch {
        return value;
    }
};

const truncate = (value, max = 120) => {
    if (!value) return "—";
    return value.length > max ? `${value.slice(0, max)}…` : value;
};

export default function Failures() {
    const { token } = useAuth();

    const authHeaders = useCallback(() => ({
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }), [token]);

    const [filters, setFilters] = useState({
        messageReference: "",
        startDate: "",
        endDate: "",
        freeText: "",
    });
    const [results, setResults] = useState([]);
    const [total, setTotal] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [searched, setSearched] = useState(false);
    const [collapsed, setCollapsed] = useState(false);
    const [expandedRow, setExpandedRow] = useState(null);
    const [copiedKey, setCopiedKey] = useState(null);
    const pageSize = 20;

    const searchFailures = useCallback((nextPage = 0) => {
        setLoading(true);
        setError(null);
        setSearched(true);

        const params = new URLSearchParams({
            page: String(nextPage),
            size: String(pageSize),
        });

        Object.entries(filters).forEach(([key, value]) => {
            if (value !== "") params.set(key, value);
        });

        fetch(`${API_BASE}?${params.toString()}`, { headers: authHeaders() })
            .then((response) => {
                if (!response.ok) throw new Error(`Failures search failed (${response.status})`);
                return response.json();
            })
            .then((payload) => {
                setResults(payload.content || []);
                setTotal(payload.totalElements || 0);
                setTotalPages(payload.totalPages || 0);
                setPage(payload.number || 0);
                setExpandedRow(null);
                setLoading(false);
            })
            .catch((ex) => {
                setError(ex.message);
                setLoading(false);
            });
    }, [authHeaders, filters]);

    const onSubmit = (event) => {
        event.preventDefault();
        searchFailures(0);
    };

    const clearFilters = () => {
        setFilters({
            messageReference: "",
            startDate: "",
            endDate: "",
            freeText: "",
        });
        setResults([]);
        setTotal(0);
        setTotalPages(0);
        setPage(0);
        setSearched(false);
        setError(null);
        setExpandedRow(null);
    };

    const copyValue = async (key, value) => {
        try {
            await navigator.clipboard.writeText(value || "");
            setCopiedKey(key);
            setTimeout(() => setCopiedKey(null), 1800);
        } catch {
            setCopiedKey(null);
        }
    };

    const pageItems = useMemo(() => Array.from({ length: totalPages }, (_, index) => index), [totalPages]);

    return (
        <>
            <div className={`search-panel${collapsed ? " panel-collapsed" : ""}`}>
                <div className="panel-section-title failures-panel-title" onClick={() => setCollapsed((value) => !value)}>
                    <div className="failures-panel-title-left">
                        <span>Failures Search</span>
                        <span className="rc-mode-chip">
                            <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                                <circle cx="12" cy="12" r="9" />
                                <line x1="12" y1="8" x2="12" y2="13" />
                                <line x1="12" y1="16" x2="12.01" y2="16" />
                            </svg>
                            Failures
                        </span>
                        {total > 0 && <span className="filter-badge">{total.toLocaleString()} found</span>}
                    </div>
                    <span className="collapse-icon">{collapsed ? "▼ Expand" : "▲ Collapse"}</span>
                </div>

                {!collapsed && (
                    <form onSubmit={onSubmit}>
                        <div className="row">
                            <div className="field-group field-group-wide">
                                <label>Message Reference</label>
                                <input
                                    placeholder="Message reference"
                                    value={filters.messageReference}
                                    onChange={(e) => setFilters((prev) => ({ ...prev, messageReference: e.target.value }))}
                                />
                            </div>
                        </div>
                        <div className="row">
                            <div className="field-group">
                                <label>Start Date</label>
                                <input type="date" value={filters.startDate} onChange={(e) => setFilters((prev) => ({ ...prev, startDate: e.target.value }))} />
                            </div>
                            <div className="field-group">
                                <label>End Date</label>
                                <input type="date" value={filters.endDate} onChange={(e) => setFilters((prev) => ({ ...prev, endDate: e.target.value }))} />
                            </div>
                        </div>
                        <div className="row">
                            <div className="field-group field-group-wide">
                                <label>Free Text</label>
                                <input
                                    className="input-wide"
                                    placeholder="Search error message, stack trace, raw input..."
                                    value={filters.freeText}
                                    onChange={(e) => setFilters((prev) => ({ ...prev, freeText: e.target.value }))}
                                />
                            </div>
                        </div>
                    </form>
                )}
            </div>

            <div className="action-bar">
                <div className="action-left">
                    <button className={`search-btn${loading ? " btn-loading" : ""}`} onClick={() => searchFailures(0)} disabled={loading}>
                        {loading
                            ? <><span className="spinner" />Searching...</>
                            : <><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><circle cx="11" cy="11" r="7" /><line x1="16.5" y1="16.5" x2="22" y2="22" /></svg>Search</>}
                    </button>
                    <button className="clear-btn" onClick={clearFilters}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><polyline points="1 4 1 10 7 10" /><path d="M3.51 15a9 9 0 102.13-9.36L1 10" /></svg>
                        Reset
                    </button>
                </div>
                <div className="action-hint">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10" /><line x1="12" y1="16" x2="12" y2="12" /><line x1="12" y1="8" x2="12.01" y2="8" /></svg>
                    Expand a row to view failure message, stack trace, and raw input
                </div>
            </div>

            {error && (
                <div className="failures-error-banner">
                    ⚠ {error}
                </div>
            )}

            {searched && (
                <>
                    <div className="stats-row">
                        <div className="stat-card failures-stat-card failures-stat-card-primary">
                            <span className="stat-value">{total.toLocaleString()}</span>
                            <span className="stat-label">Total Failures</span>
                        </div>
                        <div className="stat-card failures-stat-card failures-stat-card-secondary">
                            <span className="stat-value">{totalPages === 0 ? 0 : page + 1}</span>
                            <span className="stat-label">Current Page</span>
                        </div>
                    </div>

                    {!loading && !error && results.length === 0 && (
                        <div className="adv-empty-state">
                            <p>No failures found</p>
                            <span>Try adjusting your search filters</span>
                        </div>
                    )}

                    {!error && results.length > 0 && (
                        <div className="table-shell">
                            <div className="table-wrapper failures-table-wrapper">
                                <table className="table">
                                    <thead>
                                        <tr>
                                            <th className="row-num-th failures-expand-head" />
                                            <th>Message Reference</th>
                                            <th>Error Code</th>
                                            <th>Stage</th>
                                            <th>Input Type</th>
                                            <th>Failed At</th>
                                            <th>Failure Message</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {results.map((row, index) => (
                                            <React.Fragment key={row.id || index}>
                                                <tr
                                                    onClick={() => setExpandedRow((prev) => prev === row.id ? null : row.id)}
                                                    className="failures-result-row"
                                                >
                                                    <td className="row-num-td">
                                                        <button
                                                            type="button"
                                                            className={`failures-expand-btn${expandedRow === row.id ? " open" : ""}`}
                                                            onClick={(event) => {
                                                                event.stopPropagation();
                                                                setExpandedRow((prev) => prev === row.id ? null : row.id);
                                                            }}
                                                        >
                                                            <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                                                                <polyline points="9 18 15 12 9 6" />
                                                            </svg>
                                                        </button>
                                                    </td>
                                                    <td className="failures-mono-text failures-reference-cell">{row.messageReference || "—"}</td>
                                                    <td className="failures-code-cell">{row.errorCode || "—"}</td>
                                                    <td>{row.stage || "—"}</td>
                                                    <td>{row.inputType || "—"}</td>
                                                    <td className="failures-date-cell">{fmtDate(row.failedAt)}</td>
                                                    <td>{truncate(row.errorMessage, 140)}</td>
                                                </tr>
                                                {expandedRow === row.id && (
                                                    <tr className="failures-expanded-row">
                                                        <td colSpan={7} className="failures-expanded-cell">
                                                            <div className="failures-expanded-content">
                                                                <div>
                                                                    <div className="failures-expanded-head">
                                                                        <span className="failures-expanded-label">Failure Message</span>
                                                                        {row.errorMessage && (
                                                                            <button type="button" onClick={() => copyValue(`${row.id}-message`, row.errorMessage)} className={`failures-copy-btn${copiedKey === `${row.id}-message` ? " copied" : ""}`}>
                                                                                {copiedKey === `${row.id}-message` ? "✓ Copied" : "Copy"}
                                                                            </button>
                                                                        )}
                                                                    </div>
                                                                    <pre className="failures-pre failures-pre-message">{row.errorMessage || "No failure message available"}</pre>
                                                                </div>
                                                                <div>
                                                                    <div className="failures-expanded-head">
                                                                        <span className="failures-expanded-label">Stack Trace</span>
                                                                        {row.stackTrace && (
                                                                            <button type="button" onClick={() => copyValue(`${row.id}-stack`, row.stackTrace)} className={`failures-copy-btn${copiedKey === `${row.id}-stack` ? " copied" : ""}`}>
                                                                                {copiedKey === `${row.id}-stack` ? "✓ Copied" : "Copy"}
                                                                            </button>
                                                                        )}
                                                                    </div>
                                                                    <pre className="failures-pre failures-pre-stack">{row.stackTrace || "No stack trace available"}</pre>
                                                                </div>
                                                                <div>
                                                                    <div className="failures-expanded-head">
                                                                        <span className="failures-expanded-label">Raw Input</span>
                                                                        {row.rawInput && (
                                                                            <button type="button" onClick={() => copyValue(`${row.id}-raw`, row.rawInput)} className={`failures-copy-btn${copiedKey === `${row.id}-raw` ? " copied" : ""}`}>
                                                                                {copiedKey === `${row.id}-raw` ? "✓ Copied" : "Copy XML"}
                                                                            </button>
                                                                        )}
                                                                    </div>
                                                                    <pre className="failures-pre failures-pre-raw">{row.rawInput || "No raw input available"}</pre>
                                                                </div>
                                                            </div>
                                                        </td>
                                                    </tr>
                                                )}
                                            </React.Fragment>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    )}

                    {totalPages > 1 && (
                        <div className="pagination-bar">
                            <div className="pagination-left">
                                <span className="record-range">
                                    Showing <strong>{results.length === 0 ? 0 : page * pageSize + 1}–{Math.min((page + 1) * pageSize, total)}</strong> of <strong>{total.toLocaleString()}</strong> records
                                </span>
                            </div>
                            <div className="pagination-center">
                                <button className="pg-btn" disabled={page === 0 || loading} onClick={() => searchFailures(page - 1)}>Prev</button>
                                {pageItems.map((pageIndex) => (
                                    <button
                                        key={pageIndex}
                                        className={`pg-btn pg-num${pageIndex === page ? " pg-active" : ""}`}
                                        disabled={loading}
                                        onClick={() => searchFailures(pageIndex)}
                                    >
                                        {pageIndex + 1}
                                    </button>
                                ))}
                                <button className="pg-btn" disabled={page >= totalPages - 1 || loading} onClick={() => searchFailures(page + 1)}>Next</button>
                            </div>
                            <div className="pagination-right">
                                <span className="pg-of-total">of {totalPages}</span>
                            </div>
                        </div>
                    )}
                </>
            )}
        </>
    );
}
