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
        initializeFtsTriggers(jdbc);
        initializeVectorTable(jdbc, vectorDimension);
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
    }

    private static void initializeFtsTriggers(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS chunk_ai AFTER INSERT ON chunk BEGIN
                    INSERT INTO chunk_fts(rowid, chunk_id, document_id, search_terms)
                    VALUES (new.rowid, new.id, new.document_id, new.search_terms);
                END
                """);
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS chunk_ad AFTER DELETE ON chunk BEGIN
                    DELETE FROM chunk_fts WHERE rowid = old.rowid;
                END
                """);
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS chunk_au
                AFTER UPDATE OF id, document_id, search_terms ON chunk BEGIN
                    DELETE FROM chunk_fts WHERE rowid = old.rowid;
                    INSERT INTO chunk_fts(rowid, chunk_id, document_id, search_terms)
                    VALUES (new.rowid, new.id, new.document_id, new.search_terms);
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
