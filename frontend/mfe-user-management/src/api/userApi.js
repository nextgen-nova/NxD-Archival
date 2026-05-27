import axiosInstance from "./axiosInstance";
// All endpoints driven by REACT_APP_API_BASE_URL in .env — no hardcoded URLs
export const fetchUsers        = (params)                => axiosInstance.get("/api/users", { params });
export const fetchUserStats    = ()                      => axiosInstance.get("/api/users/stats");
export const createUser        = (data)                  => axiosInstance.post("/api/users", data);
export const updateUser        = (employeeId, data)      => axiosInstance.put(`/api/users/${employeeId}`, data);
export const toggleUserStatus  = (employeeId, active)    => axiosInstance.patch(`/api/users/${employeeId}/status`, { active });
export const deleteUser        = (employeeId)            => axiosInstance.delete(`/api/users/${employeeId}`);
export default axiosInstance;
