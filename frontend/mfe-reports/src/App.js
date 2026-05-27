import React, { useEffect } from "react";
import "./styles/mfe.css";
import "./components/Reports.css";
import { applyTheme } from "./theme";
import Reports from "./components/Reports";

export default function App() {
  useEffect(() => { applyTheme(); }, []);
  return (
    <div className="mfe-root">
      <Reports />
    </div>
  );
}
