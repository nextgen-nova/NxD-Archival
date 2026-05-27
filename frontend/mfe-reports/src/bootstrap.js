import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { applyTheme } from "./theme";

applyTheme();

const root = ReactDOM.createRoot(document.getElementById("root"));
root.render(<App />);
