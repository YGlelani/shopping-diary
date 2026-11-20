const BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export async function apiRequest(endpoint, options = {}, token = null) {
  const isFormData = options.body instanceof FormData;
  
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options.headers,
  };

  const response = await fetch(`${BASE_URL}${endpoint}`, { ...options, headers });
  
  const text = await response.text();
  
  // Safely try to parse JSON, otherwise return the raw text
  let data = text;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      // It's just plain text, leave it as is
    }
  }

  if (!response.ok) {
    const errorMsg = typeof data === 'object' ? (data?.error || data?.message) : data;
    throw new Error(errorMsg || response.statusText || 'Request failed');
  }
  return data;
}