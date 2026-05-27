/**
 * BrandLogo — file-based logo component
 *
 * To change logo for any company:
 *  1. Drop the new logo file into  src/assets/logos/
 *  2. In .env set:  REACT_APP_LOGO_FILE=your-logo.svg
 *  3. Run: npm start
 *
 * Rules:
 *  - Only ONE logo file should exist in src/assets/logos/ at a time
 *  - Supported: .svg .png .jpg .jpeg .webp .avif .gif .bmp .tiff .ico
 *  - The filename in .env MUST exactly match the file in the folder
 */

import React from "react";

const LOGO_FILE = process.env.REACT_APP_LOGO_FILE || "";

// require.context scans the folder at build time.
// We load ALL files and pick the one matching LOGO_FILE.
// This avoids static import errors when files change.
let logoSrc = null;

try {
  const ctx = require.context(
    "../assets/logos",
    false,
    /\.(svg|png|jpg|jpeg|webp|avif|gif|bmp|tiff|ico)$/i
  );
  const match = ctx.keys().find(k => k === `./${LOGO_FILE}`);
  if (match) {
    logoSrc = ctx(match);
  } else if (LOGO_FILE) {
    console.warn(
      `[BrandLogo] "${LOGO_FILE}" not found in src/assets/logos/.\n` +
      `Available files: ${ctx.keys().join(", ")}`
    );
  }
} catch (e) {
  console.warn("[BrandLogo] Could not scan logos folder:", e.message);
}

/**
 * @param {"sidebar"|"login"} variant
 */
export default function BrandLogo({ variant = "sidebar" }) {
  const style = {
    width: "100%",
    height: "100%",
    objectFit: "contain",
    display: "block",
  };

  if (logoSrc) {
    return <img src={logoSrc} alt="Brand logo" style={style} />;
  }

  // Fallback — built-in NxD SVG
  return (
    <svg style={style} viewBox="0 0 56 36" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M2 30V6h3.5l10 16V6H19v24h-3.5L5.5 14v16H2z" fill="#1ec8c8"/>
      <path d="M22 13l5 7-5 7h4l3-4.5 3 4.5h4l-5-7 5-7h-4l-3 4.5-3-4.5h-4z" fill="#1ec8c8"/>
      <path d="M37 6h6c5 0 9 4 9 12s-4 12-9 12h-6V6zm3.5 3.5v17h2.5c3 0 5.5-2.5 5.5-8.5S46 9.5 43 9.5h-2.5z" fill="#1ec8c8"/>
      <path d="M2 34 Q28 26 54 30" stroke="#1ec8c8" strokeWidth="1.5" fill="none" strokeLinecap="round" opacity="0.7"/>
    </svg>
  );
}