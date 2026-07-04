import { request } from './api.js';

const BASE_URL = import.meta.env.VITE_API_URL || '';

export const sessionService = {
  getAll: () => request('/api/sessions'),

  getById: (sessionId) => request(`/api/sessions/${sessionId}`),

  create: (title) =>
    request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  delete: (sessionId) =>
    request(`/api/sessions/${sessionId}`, {
      method: 'DELETE',
    }),

  rename: (sessionId, title) =>
    request(`/api/sessions/${sessionId}/rename`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    }),

  getMessages: (sessionId, cursor = null, size = 20) => {
    let url = `/api/sessions/${sessionId}/messages?size=${size}`;
    if (cursor) {
      url += `&cursor=${cursor}`;
    }
    return request(url);
  },

  shareViaEmail: (sessionId, email) =>
    request(`/api/sessions/${sessionId}/share/email`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    }),

  requestPdfExport: (sessionId) =>
    request(`/api/sessions/${sessionId}/export-pdf`, {
      method: 'POST',
      hideProgress: true,
    }),

  getPdfExportStatus: (sessionId, jobId) =>
    request(`/api/sessions/${sessionId}/export-pdf/${jobId}`, {
      hideProgress: true,
    }),

  /**
   * Fetches the rendered PDF as raw bytes from our own backend (no Cloudinary
   * involved) and triggers a real browser "Save As" download. This can't go
   * through the shared request() helper above since that always parses the
   * response as JSON — a PDF needs response.blob() instead.
   */
  downloadPdfExportFile: async (sessionId, jobId, fileName) => {
    const response = await fetch(`${BASE_URL}/api/sessions/${sessionId}/export-pdf/${jobId}/download`, {
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error(`Failed to download PDF: ${response.status}`);
    }

    const blob = await response.blob();
    const blobUrl = window.URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = fileName || `session-${sessionId}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(blobUrl);
  },
};
