(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.DocUtils = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const collator = new Intl.Collator("zh-CN", { numeric: true, sensitivity: "base" });
  const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  const dateTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hour12: false
  });

  function extensionOf(name) {
    if (!name) return "";
    const index = name.lastIndexOf(".");
    return index > 0 && index < name.length - 1 ? name.slice(index + 1).toLowerCase() : "";
  }

  function isVideo(name) {
    return ["mp4", "mov", "mkv", "webm", "avi", "m4v"].includes(extensionOf(name));
  }

  function normalizeQuery(query) {
    return String(query || "").trim().toLowerCase();
  }

  function matchesQuery(document, query) {
    const normalized = normalizeQuery(query);
    return !normalized || String(document?.name || "").toLowerCase().includes(normalized);
  }

  function sortDocuments(documents, sortKey = "uploaded", direction = "desc") {
    const factor = direction === "asc" ? 1 : -1;
    return [...(documents || [])].sort((left, right) => {
      let result = 0;
      if (sortKey === "uploaded") {
        result = (left.createdAt || 0) - (right.createdAt || 0);
      } else if (sortKey === "size") {
        result = (left.sizeBytes || 0) - (right.sizeBytes || 0);
      } else {
        const leftExtension = extensionOf(left.name);
        const rightExtension = extensionOf(right.name);
        if (!leftExtension && rightExtension) result = -1;
        else if (leftExtension && !rightExtension) result = 1;
        else result = collator.compare(leftExtension, rightExtension);
      }
      if (result === 0) result = collator.compare(left.name || "", right.name || "");
      return result * factor;
    });
  }

  function filterAndSortDocuments(documents, query, sortKey, direction) {
    const filtered = (documents || []).filter(document => matchesQuery(document, query));
    return sortDocuments(filtered, sortKey, direction);
  }

  function formatSize(bytes) {
    if (!bytes) return "0 B";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / 1024 / 1024).toFixed(1) + " MB";
  }

  function formatDate(timestamp) {
    return timestamp ? dateFormatter.format(timestamp) : "";
  }

  function formatDateTime(timestamp) {
    return timestamp ? dateTimeFormatter.format(timestamp) : "—";
  }

  function formatDuration(milliseconds) {
    const total = Math.max(0, Math.floor((milliseconds || 0) / 1000));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    const mmss = String(minutes).padStart(2, "0") + ":" + String(seconds).padStart(2, "0");
    return hours > 0 ? String(hours).padStart(2, "0") + ":" + mmss : mmss;
  }

  return {
    extensionOf,
    isVideo,
    matchesQuery,
    sortDocuments,
    filterAndSortDocuments,
    formatSize,
    formatDate,
    formatDateTime,
    formatDuration
  };
});
