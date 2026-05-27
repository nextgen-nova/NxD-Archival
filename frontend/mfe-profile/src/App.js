import React, { useEffect } from "react";
import { AuthProvider } from "./AuthContext";
import Profile from "./components/Profile";
import "./styles/mfe.css";
import { applyTheme } from "./theme";

export default function App() {
  useEffect(() => { applyTheme(); }, []);
  return (
    <AuthProvider>
      <div className="mfe-root">
        <Profile />
      </div>
    </AuthProvider>
  );
}
