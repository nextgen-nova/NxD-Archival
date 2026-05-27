import React, { useEffect } from "react";
import { AuthProvider } from "./AuthContext";
import Search from "./components/Search";
import "./styles/mfe.css";
import { applyTheme } from "./theme";

export default function App() {
  useEffect(() => { applyTheme(); }, []);
  return (
    <AuthProvider>
      <div className="mfe-root">
        <Search />
      </div>
    </AuthProvider>
  );
}
