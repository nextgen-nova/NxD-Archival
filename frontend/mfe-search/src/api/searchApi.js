import axiosInstance from "./axiosInstance";
// All endpoints driven by REACT_APP_API_BASE_URL in .env
export const searchMessages    = (params) => axiosInstance.get("/api/search", { params });
export const getDropdownOptions= ()       => axiosInstance.get("/api/dropdown-options");
