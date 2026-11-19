package com.example.docinsight.service;

import com.example.docinsight.config.SqliteDataSource;
import com.example.docinsight.config.SqliteExtensionLoader;
import com.example.docinsight.config.SqliteSchema;
import com.example.docinsight.mapper.DocumentQueryMapper;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
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

        DocumentStore first = new DocumentStore(jdbc, queryMapper(dataSource));
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

        DocumentStore reopened = new DocumentStore(new JdbcTemplate(dataSource), queryMapper(dataSource));

        KnowledgeBase restored = reopened.getKnowledgeBase(knowledgeBase.id());
        assertThat(restored).isNotNull();
        assertThat(restored.documentCount()).isEqualTo(1);
        assertThat(restored.chunkCount()).isEqualTo(2);
        assertThat(restored.updatedAt()).isGreaterThanOrEqualTo(restored.createdAt());
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
        assertThat(jdbc.queryForList(
                "SELECT name FROM pragma_table_info('document')", String.class))
                .contains("media_path", "duration_ms");
        List<DocumentInfo> legacyDocuments = new DocumentStore(jdbc, queryMapper(dataSource)).list();
        assertThat(legacyDocuments).hasSize(1);
        assertThat(legacyDocuments.get(0).createdAt()).isPositive();
    }

    @Test
    void persistsMediaMetadataWithoutStoringVideoBytes() throws Exception {
        Path dataDirectory = Path.of(
                "target", "test-data", "media-" + UUID.randomUUID());
        Path extension = SqliteExtensionLoader.resolve(dataDirectory, "");
        String file = dataDirectory.resolve("docinsight.db").toAbsolutePath()
                .toString().replace('\\', '/');
        DataSource dataSource = new SqliteDataSource("jdbc:sqlite:" + file, extension);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initializeSchema(dataSource, jdbc);

        DocumentStore store = new DocumentStore(jdbc, queryMapper(dataSource));
        KnowledgeBase knowledgeBase = store.createKnowledgeBase("视频库", "");
        DocumentInfo info = new DocumentInfo(
                "video1", "demo.mp4", knowledgeBase.id(), 1, 1, 2048,
                System.currentTimeMillis(), true, 65_000);
        store.save(info,
                List.of(new Chunk("video1#0", "video1", "demo.mp4",
                        "视频语音转写", "视频第一段内容", "00:05", null)),
                "video1.mp4", 65_000);

        DocumentInfo restored = new DocumentStore(new JdbcTemplate(dataSource), queryMapper(dataSource))
                .documentsForKnowledgeBase(knowledgeBase.id())
                .get(0);

        assertThat(restored.media()).isTrue();
        assertThat(restored.durationMs()).isEqualTo(65_000);
        assertThat(new DocumentStore(new JdbcTemplate(dataSource), queryMapper(dataSource)).mediaPath("video1"))
                .isEqualTo("video1.mp4");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE media_path = 'video1.mp4'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void migratesLegacyFtsWithoutLosingKeywordSearch() throws Exception {
        Path dataDirectory = Path.of(
                "target", "test-data", "fts-migration-" + UUID.randomUUID());
        Path extension = SqliteExtensionLoader.resolve(dataDirectory, "");
        String file = dataDirectory.resolve("docinsight.db").toAbsolutePath()
                .toString().replace('\\', '/');
        DataSource dataSource = new SqliteDataSource("jdbc:sqlite:" + file, extension);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initializeSchema(dataSource, jdbc);

        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ai");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_ad");
        jdbc.execute("DROP TRIGGER IF EXISTS chunk_au");
        jdbc.execute("DROP TABLE chunk_fts");
        jdbc.execute("""
                CREATE VIRTUAL TABLE chunk_fts USING fts5(
                    chunk_id UNINDEXED,
                    document_id UNINDEXED,
                    search_terms,
                    tokenize = 'unicode61'
                )
                """);
        jdbc.update("""
                INSERT INTO knowledge_base(
                    id, name, name_key, description, created_at, updated_at)
                VALUES ('kb-fts', '旧索引', '旧索引', '', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO document(
                    id, knowledge_base_id, name, pages, chunk_count, size_bytes, created_at)
                VALUES ('doc-fts', 'kb-fts', 'manual.md', 1, 1, 10, 1)
                """);
        jdbc.update("""
                INSERT INTO chunk(
                    id, document_id, chunk_order, doc_name, heading, text, location, search_terms)
                VALUES ('doc-fts#0', 'doc-fts', 0, 'manual.md', '备份策略',
                        '每日执行全量备份', '第 1 行', '备份 策略 每日 执行 全量')
                """);
        jdbc.update("""
                INSERT INTO chunk_fts(rowid, chunk_id, document_id, search_terms)
                SELECT rowid, id, document_id, search_terms FROM chunk WHERE id = 'doc-fts#0'
                """);

        SqliteSchema.initialize(dataSource, jdbc, 3);

        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chunk_fts'",
                String.class);
        assertThat(ddl).contains("contentless_delete");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_fts WHERE chunk_fts MATCH '备份'", Long.class))
                .isEqualTo(1);
        assertThat(new DocumentStore(jdbc, queryMapper(dataSource)).keywordCandidates(
                Set.of("备份"), Set.of("doc-fts"), 10))
                .extracting(candidate -> candidate.chunk().id())
                .contains("doc-fts#0");
    }

    @Test
    void fallsBackToLikeSearchWhenFtsTokenizesDifferently() throws Exception {
        Path dataDirectory = Path.of(
                "target", "test-data", "like-fallback-" + UUID.randomUUID());
        Path extension = SqliteExtensionLoader.resolve(dataDirectory, "");
        String file = dataDirectory.resolve("docinsight.db").toAbsolutePath()
                .toString().replace('\\', '/');
        DataSource dataSource = new SqliteDataSource("jdbc:sqlite:" + file, extension);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initializeSchema(dataSource, jdbc);

        DocumentStore store = new DocumentStore(jdbc, queryMapper(dataSource));
        KnowledgeBase knowledgeBase = store.createKnowledgeBase("监控对接", "");
        store.save(
                new DocumentInfo("dv", "monitor.md", knowledgeBase.id(), 1, 1, 64),
                List.of(new Chunk("dv#0", "dv", "monitor.md", "组件对接",
                        "使用 prometheusgrafana 作为聚合导出端", "第 1 行", null)));

        // FTS 把整串字母当作一个词，检索 grafana 只能靠 LIKE 兜底命中。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_fts WHERE chunk_fts MATCH '\"grafana\"'", Long.class))
                .isZero();
        assertThat(store.keywordCandidates(Set.of("grafana"), Set.of("dv"), 10))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.chunk().id()).isEqualTo("dv#0");
                    assertThat(candidate.keywordScore()).isPositive();
                });
    }

    private DocumentQueryMapper queryMapper(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource(
                "com/example/docinsight/mapper/DocumentQueryMapper.xml"));
        return new SqlSessionTemplate(factory.getObject())
                .getMapper(DocumentQueryMapper.class);
    }

    private void initializeSchema(DataSource dataSource, JdbcTemplate jdbc) {
        SqliteSchema.initialize(dataSource, jdbc, 3);
    }
}
