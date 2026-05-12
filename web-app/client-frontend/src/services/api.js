import axios from 'axios';
import keycloak from '../config/keycloak';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081',
});

let refreshPromise = null;

const refreshToken = () => {
  if (!refreshPromise) {
    refreshPromise = keycloak
      .updateToken(30)
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
};

api.interceptors.request.use((config) => {
  if (keycloak.token) {
    config.headers.Authorization = `Bearer ${keycloak.token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status !== 401 || originalRequest._retry) {
      return Promise.reject(error);
    }

    originalRequest._retry = true;

    try {
      await refreshToken();
      originalRequest.headers.Authorization = `Bearer ${keycloak.token}`;
      return api(originalRequest);
    } catch (refreshError) {
      keycloak.login();
      return Promise.reject(refreshError);
    }
  }
);

export default api;
