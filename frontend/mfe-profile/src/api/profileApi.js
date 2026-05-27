import axiosInstance from "./axiosInstance";
// All endpoints driven by REACT_APP_API_BASE_URL in .env
export const fetchProfile  = ()     => axiosInstance.get("/api/profile");
export const updateProfile = (data) => axiosInstance.put("/api/profile", data);
export const changePassword= (data) => axiosInstance.put("/api/profile/password", data);
export default axiosInstance;
