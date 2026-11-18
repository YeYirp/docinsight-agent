CREATE TABLE IF NOT EXISTS knowledge_base (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    name_key TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    source_dir TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS document (
    id TEXT PRIMARY KEY,
    knowledge_base_id TEXT,
    name TEXT NOT NULL,
    pages INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    source_path TEXT,
    media_path TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chunk (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_order INTEGER NOT NULL,
    doc_name TEXT NOT NULL,
    heading TEXT NOT NULL DEFAULT '',
    text TEXT NOT NULL,
    location TEXT NOT NULL DEFAULT '',
    search_terms TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_document_knowledge_base
    ON document(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_chunk_document_order
    ON chunk(document_id, chunk_order);

CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
    search_terms,
    content = '',
    contentless_delete = 1,
    tokenize = 'unicode61'
);
