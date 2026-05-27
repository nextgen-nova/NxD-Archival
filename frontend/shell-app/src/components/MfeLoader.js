import React from "react";
export default function MfeLoader({ name }) {
  return (
    <div className="mfe-loader">
      <div className="mfe-loader-spinner" />
      <p className="mfe-loader-text">Loading {name}…</p>
    </div>
  );
}
