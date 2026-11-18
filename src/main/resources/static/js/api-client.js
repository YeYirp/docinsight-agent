(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.ApiClient = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  async function request(url, options = {}) {
    let response;
    try {
      response = await fetch(url, options);
    } catch (error) {
      const message = String(error && error.message || "");
      if (/Failed to fetch|NetworkError|Load failed|fetch failed/i.test(message)) {
        throw new Error("无法连接到服务，请确认后端已启动");
      }
      throw error;
    }
    if (!response.ok) {
      const text = await response.text();
      let message = text;
      try {
        const parsed = JSON.parse(text);
        message = parsed.detail || parsed.message || parsed.error || text;
      } catch (_) {
        // Keep the raw response for non-JSON errors.
      }
      throw new Error(message || ("HTTP " + response.status));
    }
    if (response.status === 204) return null;
    return response.json();
  }

  return { request };
});
