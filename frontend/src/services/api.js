import NProgress from 'nprogress';
import 'nprogress/nprogress.css';

NProgress.configure({ showSpinner: false, minimum: 0.1, speed: 400 });

const BASE_URL = import.meta.env.VITE_API_URL || '';

async function request(path, options = {}, isRetry = false) {
  const isFormData = options.body instanceof FormData;
  const headers = isFormData
    ? { 'ngrok-skip-browser-warning': 'true', ...options.headers }
    : { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true', ...options.headers };
  const hideProgress = options.hideProgress === true;

  if (!hideProgress) NProgress.start();
  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      credentials: 'include',
      headers,
      ...options,
    });

    let data = null;
    try {
      data = await response.json();
    } catch {
      data = null;
    }

    if (!response.ok) {
      if (response.status === 401 && path !== '/auth/login' && path !== '/auth/refresh' && !isRetry) {
        const refreshed = await refreshAccessToken();

        if (refreshed) {
          return request(path, options, true);
        }

        window.dispatchEvent(new Event('auth-expired'));
      }

      throw new Error(data?.message || `Request failed: ${response.status}`);
    }

    if (!hideProgress) NProgress.done();
    return data;
  } catch (error) {
    if (!hideProgress) NProgress.done();
    throw error;
  }
}

async function refreshAccessToken() {
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  });

  return response.ok;
}

export { request };
