import React, { useState } from "react";
import { useAuth } from "../AuthContext";
import BrandLogo from "./BrandLogo";

export default function Navbar({ onOpenTab, appName = "SWIFT Platform" }) {
  const { user } = useAuth();
  const [collapsed, setCollapsed] = useState(false);

  const navItems = [
    {
      route: "search",
      label: "Message Search",
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="11" cy="11" r="8" />
          <path d="m21 21-4.35-4.35" />
        </svg>
      ),
    },
    ...(user?.role === "ADMIN"
      ? [
          {
            route: "users",
            label: "User Management",
            icon: (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            ),
          },
        ]
      : []),
    {
      route: "reports",
      label: "Reports",
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M3 3h18v18H3z" />
          <path d="M7 8h10" />
          <path d="M7 12h10" />
          <path d="M7 16h6" />
        </svg>
      ),
    },
    {
      route: "profile",
      label: "Profile",
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
        </svg>
      ),
    },
  ];

  return (
    <aside className={`shell-sidebar ${collapsed ? "collapsed" : ""}`}>
      <div className="sidebar-brand">
        <div className="brand-icon" style={{ background: "transparent", padding: 0, overflow: "hidden" }}>
          <BrandLogo variant="sidebar" />
        </div>
        {!collapsed && (
          <div className="brand-text">
            <span className="brand-name">{appName.split(" ")[0]}</span>
            <span className="brand-tag">{appName.split(" ").slice(1).join(" ") || "Platform"}</span>
          </div>
        )}
      </div>

      <nav className="sidebar-nav">
        {!collapsed && <p className="sidebar-section-label">Menu</p>}
        {navItems.map((item) => (
          <button key={item.route} className="sidebar-link" onClick={() => onOpenTab(item.route)} title={item.label}>
            {item.icon}
            {!collapsed && <span>{item.label}</span>}
          </button>
        ))}
      </nav>

      <div className="sidebar-bottom">
        <button
          className="sidebar-action-btn"
          onClick={() => setCollapsed(!collapsed)}
          style={{ borderBottom: "1px solid var(--border)", marginBottom: 4, paddingBottom: 12 }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: collapsed ? "center" : "flex-start", gap: 10, width: "100%" }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              {collapsed ? <polyline points="9 18 15 12 9 6" /> : <polyline points="15 18 9 12 15 6" />}
            </svg>
            {!collapsed && <span>Collapse</span>}
          </div>
        </button>
      </div>
    </aside>
  );
}
