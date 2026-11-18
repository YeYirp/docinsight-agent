package com.example.docinsight.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;

/** 统一执行 SQLite 普通表、FTS5、触发器和 vec0 虚拟表初始化。 */
public final class SqliteSchema {

    private SqliteSchema() {
    }

    public static void initialize(DataSource dataSource, JdbcTemplate jdbc, int vectorDimension) {
        if (vectorDimension <= 0 || vectorDimension > 65_536) {
            throw new IllegalArgumentException("EMBEDDING_DIMENSION 必须在 1 到 65536 之间");
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql"));
        populator.execute(dataSource);
        ensureDocumentCreatedAt(jdbc);
        ensureKnowledgeBaseUpdatedAt(jdbc);
        ensureDocumentMediaColumns(jdbc);
        ensureDocumentSourceColumn(jdbc);
        ensureContentlessFts(jdbc);
        initializeFtsTriggers(jdbc);
        initializeVectorTable(jdbc, vectorDimension);
        jdbc.execute("PRAGMA optimize");
    }

    private static void ensureKnowledgeBaseUpdatedAt(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('knowledge_base')", String.class);
        if (!columns.contains("updated_at")) {
            jdbc.execute("ALTER TABLE knowledge_base ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        }
        if (!columns.contains("source_dir")) {
            jdbc.execute("ALTER TABLE knowledge_base ADD COLUMN source_dir TEXT NOT NULL DEFAULT ''");
        }
        jdbc.update("""
                UPDATE knowledge_base
                SET updated_at = COALESCE(
                    (SELECT MAX(d.created_at)
                     FROM document d
                     WHERE d.knowledge_base_id = knowledge_base.id),
                    created_at)
                WHERE updated_at = 0
                """);
        jdbc.update("""
                UPDATE knowledge_base
                SET source_dir = name
                WHERE source_dir = ''
                """);
    }

    /** 兼容旧版本数据库：document 表早期没有上传时间字段。 */
    private static void ensureDocumentCreatedAt(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('document')", String.class);
        if (!columns.contains("created_at")) {
            jdbc.execute("ALTER TABLE document ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
        }
        jdbc.update("""
                UPDATE document
                SET created_at = COALESCE(
                    (SELECT kb.created_at
                     FROM knowledge_base kb
                     WHERE kb.id = document.knowledge_base_id),
                    ?)
                WHERE created_at = 0
                """, System.currentTimeMillis());
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_knowledge_base_created
                ON document(knowledge_base_id, created_at)
                """);
    }

    /** 兼容旧版本数据库：视频文件只保存磁盘引用和时长，不把二进制写入 SQLite。 */
    private static void ensureDocumentMediaColumns(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('document')", String.class);
        if (!columns.contains("media_path")) {
            jdbc.execute("ALTER TABLE document ADD COLUMN media_path TEXT");
        }
        if (!columns.contains("duration_ms")) {
            jdbc.execute("ALTER TABLE document ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static void ensureDocumentSourceColumn(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('document')", String.class);
        if (!columns.contains("source_path")) {
            jdbc.execute("ALTER TABLE document ADD COLUMN source_path TEXT");
        }
    }

    /**
     * 旧版 FTS 表会保存 chunk_id、document_id 和 search_terms 的副本。
     * 新版只保留索引，不保存正文副本，首次启动时自动重建一次。
     */
    private static void ensureContentlessFts(JdbcTemplate jdbc) {
        List<String> existing = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chunk_fts'",
                String.class);
        if (existing.isEmpty()) {
            createContentlessFts(jdbc);
            return;
        }
        String ddl = existing.get(0);
        if (ddl != null && ddl.contains("contentless_delete")) {
            return;
        }
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ai");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ad");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_au");
        jdbc.execute("DROP TABLE IF EXISTS chunk_fts");
        createContentlessFts(jdbc);
        jdbc.update("""
                INSERT INTO chunk_fts(rowid, search_terms)
                SELECT rowid, search_terms FROM chunk
                """);
    }

    private static void createContentlessFts(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
                    search_terms,
                    content = '',
                    contentless_delete = 1,
                    tokenize = 'unicode61'
                )
                """);
    }

    private static void initializeFtsTriggers(JdbcTemplate jdbc) {
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ai");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ad");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_au");
        jdbc.execute("""
                CREATE TRIGGER chunk_ai AFTER INSERT ON chunk BEGIN
                    INSERT INTO chunk_fts(rowid, search_terms)
                    VALUES (new.rowid, new.search_terms);
                END
                """);
        jdbc.execute("""
                CREATE TRIGGER chunk_ad AFTER DELETE ON chunk BEGIN
                    DELETE FROM chunk_fts WHERE rowid = old.rowid;
                END
                """);
        jdbc.execute("""
                CREATE TRIGGER chunk_au
                AFTER UPDATE OF id, document_id, search_terms ON chunk BEGIN
                    DELETE FROM chunk_fts WHERE rowid = old.rowid;
                    INSERT INTO chunk_fts(rowid, search_terms)
                    VALUES (new.rowid, new.search_terms);
                END
                """);
    }

    private static void initializeVectorTable(JdbcTemplate jdbc, int vectorDimension) {
        List<String> existing = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chunk_vector'",
                String.class);
        if (existing.isEmpty()) {
            jdbc.execute("""
                    CREATE VIRTUAL TABLE chunk_vector USING vec0(
                        chunk_key INTEGER PRIMARY KEY,
                        embedding FLOAT[%d]
                    )
                    """.formatted(vectorDimension));
            return;
        }
        String ddl = existing.get(0);
        if (ddl == null || !ddl.matches("(?is).*float\\s*\\[\\s*" + vectorDimension + "\\s*\\].*")) {
            throw new IllegalStateException(
                    "现有 chunk_vector 维度与 EMBEDDING_DIMENSION=" + vectorDimension
                            + " 不一致。请恢复原维度，或备份后删除数据文件重新建库。");
        }
    }
}
