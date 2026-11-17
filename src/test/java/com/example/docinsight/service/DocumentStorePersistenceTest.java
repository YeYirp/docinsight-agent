package com.example.docinsight.service;

import com.example.docinsight.config.SqliteDataSource;
import com.example.docinsight.config.SqliteExtensionLoader;
import com.example.docinsight.config.SqliteSchema;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorePersistenceTest {

    @Test
    void reloadsSqliteDataFtsIndexAndVec0Vectors() throws Exception {
        Path dataDirectory = Path.of(
                "target", "test-data", "persistence-" + UUID.randomUUID());
        Path extension = SqliteExtensionLoader.resolve(dataDirectory, "");
        String file = dataDirectory.resolve("docinsight.db").toAbsolutePath()
                .toString().replace('\\', '/');
        DataSource dataSource = new SqliteDataSource("jdbc:sqlite:" + file, extension);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initializeSchema(dataSource, jdbc);

        DocumentStore first = new DocumentStore(jdbc);
        KnowledgeBase knowledgeBase = first.createKnowledgeBase("运维手册", "SQLite 持久化验证");
        first.save(
                new DocumentInfo("d1", "manual.md", knowledgeBase.id(), 1, 2, 128),
                List.of(
                        new Chunk("d1#0", "d1", "manual.md", "备份策略",
                                "每日执行全量备份", "第 1 行", null),
                        new Chunk("d1#1", "d1", "manual.md", "账号锁定",
                                "连续错误五次锁定账号", "第 2 行", null)));
        first.updateVectors(List.of(
                new Chunk("d1#0", "d1", "manual.md", "备份策略",
                        "每日执行全量备份", "第 1 行", new double[]{0.1, 0.2, 0.3})));

        DocumentStore reopened = new DocumentStore(new JdbcTemplate(dataSource));

        KnowledgeBase restored = reopened.getKnowledgeBase(knowledgeBase.id());
        assertThat(restored).isNotNull();
        assertThat(restored.documentCount()).isEqualTo(1);
        assertThat(restored.chunkCount()).isEqualTo(2);
        assertThat(reopened.documentsForKnowledgeBase(knowledgeBase.id()))
                .extracting(DocumentInfo::name)
                .containsExactly("manual.md");
        assertThat(reopened.documentsForKnowledgeBase(knowledgeBase.id()))
                .extracting(DocumentInfo::createdAt)
                .allMatch(createdAt -> createdAt > 0);
        assertThat(reopened.chunksForDocuments(Set.of("d1"))).hasSize(2);
        assertThat(reopened.chunksWithoutVectors(Set.of("d1")))
                .extracting(Chunk::id)
                .containsExactly("d1#1");

        assertThat(reopened.keywordCandidates(Set.of("备份"), Set.of("d1"), 10))
                .extracting(candidate -> candidate.chunk().id())
                .contains("d1#0");
        assertThat(reopened.vectorCandidates(
                new double[]{0.1, 0.2, 0.3}, Set.of("d1"), 10))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.chunk().id()).isEqualTo("d1#0");
                    assertThat(candidate.distance()).isLessThan(0.001);
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunk_vector", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT vec_version()", String.class)).isNotBlank();

        assertThat(reopened.removeKnowledgeBase(knowledgeBase.id())).isTrue();
        assertThat(reopened.listKnowledgeBases()).isEmpty();
        assertThat(reopened.list()).isEmpty();
        assertThat(reopened.totalChunks()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunk_vector", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunk_fts", Long.class)).isZero();
    }

    @Test
    void addsUploadTimeColumnToLegacyDatabase() throws Exception {
        Path dataDirectory = Path.of(
                "target", "test-data", "legacy-" + UUID.randomUUID());
        Path extension = SqliteExtensionLoader.resolve(dataDirectory, "");
        String file = dataDirectory.resolve("docinsight.db").toAbsolutePath()
                .toString().replace('\\', '/');
        DataSource dataSource = new SqliteDataSource("jdbc:sqlite:" + file, extension);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE document (
                    id TEXT PRIMARY KEY,
                    knowledge_base_id TEXT,
                    name TEXT NOT NULL,
                    pages INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    size_bytes INTEGER NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO document(id, knowledge_base_id, name, pages, chunk_count, size_bytes)
                VALUES ('legacy', NULL, 'old.pdf', 1, 1, 10)
                """);

        SqliteSchema.initialize(dataSource, jdbc, 3);

        assertThat(jdbc.queryForObject(
                "SELECT created_at FROM document WHERE id = 'legacy'", Long.class))
                .isPositive();
        List<DocumentInfo> legacyDocuments = new DocumentStore(jdbc).list();
        assertThat(legacyDocuments).hasSize(1);
        assertThat(legacyDocuments.get(0).createdAt()).isPositive();
    }

    private void initializeSchema(DataSource dataSource, JdbcTemplate jdbc) {
        SqliteSchema.initialize(dataSource, jdbc, 3);
    }
}
