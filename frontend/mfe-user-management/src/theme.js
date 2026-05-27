/**
 * theme.js  — shared across all MFEs
 * Reads REACT_APP_* env vars and injects every CSS custom property onto :root.
 * Call applyTheme() once in each MFE's bootstrap/App.
 *
 * To re-brand without touching code:
 *   edit .env → REACT_APP_PRIMARY_COLOR=#10b981
 *   npm run build
 */

// ── Static design tokens (mirrors mfe.css :root) ─────────────────────────────
const staticTokens = {
  "--white":            "#ffffff",
  "--black":            "#0f172a",
  "--black-3":          "#1e293b",
  "--gray-1":           "#334155",
  "--gray-2":           "#64748b",
  "--gray-3":           "#94a3b8",
  "--gray-4":           "#cbd5e1",
  "--gray-5":           "#e2e8f0",
  "--gray-6":           "#f1f5f9",
  "--gray-7":           "#f8fafc",
  "--ok":               "#059669",
  "--ok-light":         "#ecfdf5",
  "--ok-border":        "#b7dfc5",
  "--danger":           "#dc2626",
  "--danger-light":     "#fef2f2",
  "--danger-border":    "#f5c6c2",
  "--warn":             "#9a6500",
  "--warn-light":       "#fff8e6",
  "--warn-border":      "#f5d9a8",
  "--highlight-bg":     "#fff3b0",
  "--highlight-color":  "#7a5000",
  "--info":             "#1a5fa3",
  "--radius-sm":        "5px",
  "--radius":           "8px",
  "--radius-md":        "12px",
  "--radius-lg":        "14px",
  "--shadow":           "0 2px 6px rgb(0 0 0 / 0.05), 0 1px 3px rgb(0 0 0 / 0.04)",
  "--shadow-md":        "0 8px 16px rgb(0 0 0 / 0.08), 0 3px 6px rgb(0 0 0 / 0.05)",
  "--shadow-modal":     "0 20px 60px rgba(10,10,10,0.14), 0 8px 20px rgba(10,10,10,0.08)",
  "--overlay-bg":       "rgba(10,10,10,0.40)",
  "--focus-ring":       "0 0 0 3px rgba(10,10,10,0.06)",
};

// ── Brand tokens from .env ────────────────────────────────────────────────────
function envTokens() {
  return {
    "--font":             process.env.REACT_APP_FONT             || "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
    "--heading-color":    process.env.REACT_APP_HEADING_COLOR    || "#0f172a",
    "--primary-color":    process.env.REACT_APP_PRIMARY_COLOR    || "#3b82f6",
    "--background-color": process.env.REACT_APP_BACKGROUND_COLOR || "#f8fafc",
    "--accent-color":     process.env.REACT_APP_ACCENT_COLOR     || "#6366f1",
    "--data-color":       process.env.REACT_APP_TEXT_COLOR       || "#475569",
    "--accent":           process.env.REACT_APP_ACCENT_COLOR     || "#6366f1",
    "--accent-light":     tintHex(process.env.REACT_APP_ACCENT_COLOR || "#6366f1", 0.92),
    "--accent-mid":       tintHex(process.env.REACT_APP_ACCENT_COLOR || "#6366f1", 0.70),
    "--accent-hover-bg":  tintHex(process.env.REACT_APP_PRIMARY_COLOR || "#3b82f6", 0.94),
    "--mono":             "ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, Consolas, monospace",
    "--font-mono":        "ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, Consolas, monospace",
  };
}

// ── applyTheme — injects all tokens onto :root ────────────────────────────────
export function applyTheme(overrides = {}) {
  const root   = document.documentElement;
  const tokens = { ...staticTokens, ...envTokens(), ...overrides };
  Object.entries(tokens).forEach(([k, v]) => root.style.setProperty(k, v));
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function tintHex(hex, amount) {
  try {
    const clean = hex.replace("#", "");
    const full  = clean.length === 3 ? clean.split("").map(c => c + c).join("") : clean;
    const r = parseInt(full.slice(0, 2), 16);
    const g = parseInt(full.slice(2, 4), 16);
    const b = parseInt(full.slice(4, 6), 16);
    const tr = Math.round(r + (255 - r) * amount);
    const tg = Math.round(g + (255 - g) * amount);
    const tb = Math.round(b + (255 - b) * amount);
    return `rgb(${tr},${tg},${tb})`;
  } catch { return "#f0f4ff"; }
}

export default { applyTheme };
