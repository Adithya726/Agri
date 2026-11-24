import axios from "axios";

const STORAGE_KEY = "agrisense_farmer";

// Endpoints that answer 401 for wrong credentials, not for a missing session.
const PUBLIC_PATHS = ["/farmerapi/login", "/farmerapi/register"];

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL
});

const getStoredToken = () => {
    try {
        const stored = sessionStorage.getItem(STORAGE_KEY);

        return stored ? JSON.parse(stored)?.token : null;
    } catch {
        return null;
    }
};

const isPublicRequest = (config) =>
    PUBLIC_PATHS.some((path) => (config?.url || "").startsWith(path));

/*
 * Attach the login token to every API call.
 */
api.interceptors.request.use((config) => {
    const token = getStoredToken();

    if (token && !isPublicRequest(config)) {
        config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
});

/*
 * A 401 on a protected call means the token is missing, invalid or
 * expired: clear the session and send the farmer back to login.
 */
api.interceptors.response.use(
    (response) => response,
    (error) => {
        if (
            error.response?.status === 401 &&
            !isPublicRequest(error.config)
        ) {
            sessionStorage.removeItem(STORAGE_KEY);

            if (window.location.pathname !== "/login") {
                window.location.assign("/login");
            }
        }

        return Promise.reject(error);
    }
);

export default api;
