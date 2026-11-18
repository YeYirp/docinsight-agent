const test = require("node:test");
const assert = require("node:assert/strict");
const DocUtils = require("../../main/resources/static/js/doc-utils.js");

const documents = [
  { name: "售后指南.txt", sizeBytes: 300, createdAt: 20 },
  { name: "无扩展名", sizeBytes: 50, createdAt: 40 },
  { name: "产品手册.pdf", sizeBytes: 500, createdAt: 10 },
  { name: "安装说明.md", sizeBytes: 100, createdAt: 30 }
];

test("按名称搜索，支持扩展名和大小写不敏感", () => {
  assert.deepEqual(
    DocUtils.filterAndSortDocuments(documents, ".PDF", "uploaded", "desc")
      .map(document => document.name),
    ["产品手册.pdf"]
  );
  assert.equal(DocUtils.matchesQuery(documents[0], "售后"), true);
  assert.equal(DocUtils.matchesQuery(documents[0], ".md"), false);
});

test("按文件类型排序时，升序没有扩展名的文件排在最前面", () => {
  assert.deepEqual(
    DocUtils.sortDocuments(documents, "type", "asc").map(document => document.name),
    ["无扩展名", "安装说明.md", "产品手册.pdf", "售后指南.txt"]
  );
  assert.deepEqual(
    DocUtils.sortDocuments(documents, "type", "desc").map(document => document.name),
    ["售后指南.txt", "产品手册.pdf", "安装说明.md", "无扩展名"]
  );
});

test("上传时间和文件大小支持升序降序", () => {
  assert.deepEqual(
    DocUtils.sortDocuments(documents, "uploaded", "asc").map(document => document.name),
    ["产品手册.pdf", "售后指南.txt", "安装说明.md", "无扩展名"]
  );
  assert.deepEqual(
    DocUtils.sortDocuments(documents, "size", "desc").map(document => document.name),
    ["产品手册.pdf", "售后指南.txt", "安装说明.md", "无扩展名"]
  );
});

test("格式化函数保持原有输出", () => {
  assert.equal(DocUtils.formatSize(0), "0 B");
  assert.equal(DocUtils.formatSize(1536), "1.5 KB");
  assert.equal(DocUtils.formatDateTime(0), "—");
  assert.equal(DocUtils.extensionOf("archive.tar.gz"), "gz");
  assert.equal(DocUtils.extensionOf("README"), "");
  assert.equal(DocUtils.isVideo("demo.MP4"), true);
  assert.equal(DocUtils.isVideo("manual.pdf"), false);
  assert.equal(DocUtils.formatDuration(3723000), "01:02:03");
  assert.equal(DocUtils.formatDuration(65000), "01:05");
});
