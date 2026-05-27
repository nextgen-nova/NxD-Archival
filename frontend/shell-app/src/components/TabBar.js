import React, { useRef, useState, useEffect } from "react";

const ICONS = {
  search:  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>,
  reports: <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 3h18v18H3z"/><path d="M7 8h10"/><path d="M7 12h10"/><path d="M7 16h6"/></svg>,
  users:   <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>,
  profile: <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>,
};

export default function TabBar({ tabs, activeTabId, onSwitch, onClose, rightSlot = null }) {
  const scrollRef  = useRef(null);
  const [locked,   setLocked]  = useState(false);
  const [shakeId,  setShakeId] = useState(null);

  // Listen for the event dispatched by the Search MFE
  useEffect(() => {
    const handler = (e) => setLocked(e.detail?.open ?? false);
    window.addEventListener("swift:modalsOpen", handler);
    return () => window.removeEventListener("swift:modalsOpen", handler);
  }, []);

  if (tabs.length === 0) return null;

  const shake = (id) => {
    setShakeId(id);
    setTimeout(() => setShakeId(null), 550);
  };

  const handleSwitch = (tabId) => {
    if (locked && tabId !== activeTabId) { shake(tabId); return; }
    onSwitch(tabId);
  };

  const handleClose = (tabId, e) => {
    if (e) e.stopPropagation();
    if (locked) { shake(tabId); return; }
    onClose(tabId, e);
  };

  return (
    <div className="tabbar-wrapper">
      <div className="tabbar-scroll" ref={scrollRef}>
        {tabs.map(tab => {
          const isActive   = tab.id === activeTabId;
          const isBlocked  = locked && !isActive;
          return (
            <div
              key={tab.id}
              className={[
                "tabbar-tab",
                isActive  ? "active"     : "",
                isBlocked ? "tab-locked" : "",
                shakeId === tab.id ? "tab-shake" : "",
              ].filter(Boolean).join(" ")}
              onClick={() => handleSwitch(tab.id)}
              onMouseDown={e => { if (e.button === 1) { e.preventDefault(); handleClose(tab.id, e); } }}
              title={isBlocked ? "Close all popups before switching tabs" : tab.label}
            >
              <span className="tab-icon">{ICONS[tab.icon] || ICONS.search}</span>
              <span className="tab-label">{tab.label}</span>

              {/* Lock icon on non-active tabs when popups are open */}
              {isBlocked && (
                <span className="tab-lock-icon">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                    <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                </span>
              )}

              <button
                className="tab-close"
                onClick={e => handleClose(tab.id, e)}
                title={locked ? "Close popups first" : "Close"}
              >
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                  <line x1="18" y1="6" x2="6" y2="18"/>
                  <line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
              {isActive && <div className="tab-active-bar" />}
            </div>
          );
        })}

        {/* Lock notice at end of tab bar */}
        {locked && (
          <div className="tabbar-lock-notice">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
              <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
            </svg>
            Tabs locked — close popups first
          </div>
        )}
      </div>
      {rightSlot && <div className="tabbar-tools">{rightSlot}</div>}
    </div>
  );
}
