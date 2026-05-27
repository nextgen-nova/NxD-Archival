import React from "react";
export default class ErrorBoundary extends React.Component {
  constructor(props) { super(props); this.state = { hasError: false, error: null }; }
  static getDerivedStateFromError(error) { return { hasError: true, error }; }
  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <div className="mfe-error-boundary">
        <div className="mfe-error-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
        </div>
        <h3>Failed to load {this.props.mfeName}</h3>
        <p>This module could not be loaded. Please check the service is running.</p>
        {this.state.error && <div className="mfe-error-detail">{this.state.error.message}</div>}
        <button className="mfe-error-retry" onClick={() => this.setState({ hasError: false, error: null })}>
          Try Again
        </button>
      </div>
    );
  }
}
