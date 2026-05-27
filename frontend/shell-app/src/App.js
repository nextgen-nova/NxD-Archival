import React, { Suspense, lazy, useState, useCallback, useEffect } from "react";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider, useAuth } from "./AuthContext";
import Login from "./components/Login";
import Navbar from "./components/Navbar";
import TabBar from "./components/TabBar";
import MfeLoader from "./components/MfeLoader";
import ErrorBoundary from "./components/ErrorBoundary";
import QuickAccessMenu from "./components/QuickAccessMenu";
import "./styles/global.css";
import { applyTheme } from "./theme";

const UserManagementApp = lazy(() => import("user_management/App"));
const SearchApp         = lazy(() => import("search/App"));
const ProfileApp        = lazy(() => import("profile/App"));
const ReportsApp        = lazy(() => import("reports/App"));

// App name from .env — soft coded
const APP_NAME     = process.env.REACT_APP_APP_NAME     || "SWIFT Platform";
const APP_SUBTITLE = process.env.REACT_APP_APP_SUBTITLE || "Financial Messaging Intelligence";

const MFE_MAP = {
  search:  { label: "Message Search",  component: SearchApp,         icon: "search"  },
  reports: { label: "Reports",         component: ReportsApp,        icon: "reports" },
  users:   { label: "User Management", component: UserManagementApp, icon: "users"   },
  profile: { label: "Profile",         component: ProfileApp,        icon: "profile" },
};

let tabCounter = 0;

function AppRoutes() {
  const { user, loading } = useAuth();
  const [tabs,        setTabs]       = useState([{ id: ++tabCounter, route: "search", label: "Message Search", icon: "search" }]);
  const [activeTabId, setActiveTabId] = useState(tabCounter);

  const openTab = useCallback((route) => {
    const def = MFE_MAP[route];
    if (!def) return;
    setTabs(prev => {
      // If a tab for this route already exists, just switch to it
      const existing = prev.find(t => t.route === route);
      if (existing) {
        setActiveTabId(existing.id);
        return prev;
      }
      // Otherwise open a new tab
      const newTab = { id: ++tabCounter, route, label: def.label, icon: def.icon };
      setActiveTabId(newTab.id);
      return [...prev, newTab];
    });
  }, []);

  const closeTab = useCallback((tabId, e) => {
    e?.stopPropagation();
    setTabs(prev => {
      const idx  = prev.findIndex(t => t.id === tabId);
      const next = prev.filter(t => t.id !== tabId);
      if (tabId === activeTabId && next.length > 0)
        setActiveTabId(next[Math.min(idx, next.length - 1)].id);
      return next;
    });
  }, [activeTabId]);

  const switchTab = useCallback((tabId) => setActiveTabId(tabId), []);

  if (loading) return (
    <div className="shell-loading">
      <div className="shell-spinner" />
      <span>Loading {APP_NAME}…</span>
    </div>
  );

  if (!user) return <Login appName={APP_NAME} appSubtitle={APP_SUBTITLE} />;

  const activeTab       = tabs.find(t => t.id === activeTabId);
  const ActiveComponent = activeTab ? MFE_MAP[activeTab.route]?.component : null;

  return (
    <div className="shell-layout">
      <Navbar onOpenTab={openTab} appName={APP_NAME} />
      <div className="shell-content-area">
        <TabBar
          tabs={tabs}
          activeTabId={activeTabId}
          onSwitch={switchTab}
          onClose={closeTab}
          rightSlot={<QuickAccessMenu onOpenTab={openTab} />}
        />
        <main className="shell-main">
          {ActiveComponent && (
            <ErrorBoundary key={activeTab.id} mfeName={activeTab.label}>
              <Suspense fallback={<MfeLoader name={activeTab.label} />}>
                <ActiveComponent />
              </Suspense>
            </ErrorBoundary>
          )}
          {tabs.length === 0 && (
            <div className="shell-empty">
              <p>No tabs open. Click a menu item to get started.</p>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

export default function App() {
  useEffect(() => { applyTheme(); }, []);
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  );
}
