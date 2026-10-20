/** Same-origin session cookies stay in the browser; no credentials are stored in JavaScript storage. */
let csrf;

export class ApiError extends Error {
  constructor(message, status, details = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

export function resetCsrf() { csrf = undefined; }

export async function request(path, { method = 'GET', body, headers = {}, silent = false, timeoutMs = 30000 } = {}) {
  const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (mutation && !csrf) csrf = await request('/api/auth/csrf', { silent: true });
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  try {
    response = await fetch(path, {
      method, credentials: 'same-origin', signal: controller.signal,
      headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(mutation ? { [csrf.headerName]: csrf.token } : {}), ...headers },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {})
    });
  } catch (error) {
    throw new ApiError(error.name === 'AbortError'
      ? 'The request took too long. Please check your connection and try again.'
      : 'We could not reach the server. Please check your connection and try again.', 0);
  } finally { clearTimeout(timeout); }
  if (response.status === 204) return null;
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && !silent) window.dispatchEvent(new Event('voltway:session-expired'));
    if (response.status === 403) resetCsrf();
    throw new ApiError(data.message || (response.status === 403 ? 'This action is not permitted. Refresh the page if your session has changed.' : 'The request could not be completed.'), response.status, data);
  }
  return data;
}
