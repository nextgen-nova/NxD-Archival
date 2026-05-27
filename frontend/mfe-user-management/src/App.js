import React, { useEffect } from "react";
import { AuthProvider } from "./AuthContext";
import UserManagement from "./components/UserManagement";
import "./styles/mfe.css";
import { applyTheme } from "./theme";

export default function App() {
  useEffect(() => { applyTheme(); }, []);
  return (
    <AuthProvider>
      <div className="mfe-root">
        <UserManagement />
      </div>
    </AuthProvider>
  );
}
