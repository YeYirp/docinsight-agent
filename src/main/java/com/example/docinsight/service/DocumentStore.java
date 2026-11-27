package com.example.docinsight.service;

import com.example.docinsight.mapper.ChunkRow;
import com.example.docinsight.mapper.DocumentQueryMapper;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQLite 是知识库、文档、文本切片、FTS5 索引和 vec0 向量的唯一数据源。 */
@Service
public class DocumentStore {

    public record VectorCandidate(Chunk chunk, double distance) {
    }

    public record KeywordCandidate(Chunk chunk, double keywordScore, double headingScore) {
    }

    public record DocumentPaths(String docId, String sourcePath, String mediaPath) {
    }

    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");

    private static final String KNOWLEDGE_BASE_SELECT = """
            SELECT kb.id, kb.name, kb.description, kb.created_at, kb.updated_at,
                   kb.source_dir,
                   (SELECT COUNT(*) FROM document d
                    WHERE d.knowledge_base_id = kb.id) AS document_count,
                   (SELECT COALESCE(SUM(d.chunk_count), 0) FROM document d
                    WHERE d.knowledge_base_id = kb.id) AS chunk_count
            FROM knowledge_base kb
            """;

    private final JdbcTemplate jdbc;
    private final DocumentQueryMapper queries;

    public DocumentStore(JdbcTemplate jdbc, DocumentQueryMapper queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        return createKnowledgeBase(name, description, name);
    }

    public KnowledgeBase createKnowledgeBase(String name, String description,
                                             String sourceDir) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                            INSERT INTO knowledge_base(
                                id, name, name_key, description, created_at, updated_at, source_dir)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    id,
                    normalizedName,
                    normalizedName.toLowerCase(Locale.ROOT),
                    description == null ? "" : description.trim(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    sourceDir == null ? "" : sourceDir);
        } catch (DataIntegrityViolationException error) {
            throw new IllegalArgumentException("同名知识库已存在");
        }
        return getKnowledgeBase(id);
    }

    public List<DocumentPaths> documentPathsForKnowledgeBase(String knowledgeBaseId) {
        return jdbc.query("""
                        SELECT id, source_path, media_path
                        FROM document
                        WHERE knowledge_base_id = ?
                          AND (source_path IS NOT NULL OR media_path IS NOT NULL)
                        """,
                (rs, rowNum) -> new DocumentPaths(
                        rs.getString("id"),
                        rs.getString("source_path"),
                        rs.getString("media_path")),
                knowledgeBaseId);
    }

    @Transactional
    public KnowledgeBase updateKnowledgeBaseWithPaths(
            String id, String name, String description, String sourceDir,
            Map<String, String> pathUpdates) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        try {
            jdbc.update("""
                    UPDATE knowledge_base
                    SET name = ?, name_key = ?, description = ?, updated_at = ?, source_dir = ?
                    WHERE id = ?
                    """,
                    normalizedName,
                    normalizedName.toLowerCase(Locale.ROOT),
                    description == null ? "" : description.trim(),
                    System.currentTimeMillis(),
                    sourceDir == null ? "" : sourceDir,
                    id);
        } catch (DataIntegrityViolationException error) {
            throw new IllegalArgumentException("同名知识库已存在");
        }
        for (DocumentPaths paths : documentPathsForKnowledgeBase(id)) {
            String source = pathUpdates.getOrDefault(paths.sourcePath(), paths.sourcePath());
            String media = pathUpdates.getOrDefault(paths.mediaPath(), paths.mediaPath());
            if (!java.util.Objects.equals(source, paths.sourcePath())
                    || !java.util.Objects.equals(media, paths.mediaPath())) {
                jdbc.update("""
                        UPDATE document SET source_path = ?, media_path = ? WHERE id = ?
                        """, source, media, paths.docId());
            }
        }
        return getKnowledgeBase(id);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return jdbc.query(KNOWLEDGE_BASE_SELECT
                        + " ORDER BY updated_at DESC, kb.created_at DESC, kb.id",
                knowledgeBaseMapper());
    }

    public KnowledgeBase getKnowledgeBase(String id) {
        return jdbc.query(KNOWLEDGE_BASE_SELECT + " WHERE kb.id = ?",
                        knowledgeBaseMapper(), id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public boolean removeKnowledgeBase(String id) {
        jdbc.update("""
                DELETE FROM chunk_vector
                WHERE chunk_key IN (
                    SELECT c.rowid
                    FROM chunk c
                    JOIN document d ON d.id = c.document_id
                    WHERE d.knowledge_base_id = ?
                )
                """, id);
        return jdbc.update("DELETE FROM knowledge_base WHERE id = ?", id) > 0;
    }

    public List<DocumentInfo> documentsForKnowledgeBase(String knowledgeBaseId) {
        return jdbc.query("""
                        SELECT id, name, knowledge_base_id, pages, chunk_count, size_bytes, created_at,
                               (media_path IS NOT NULL) AS has_media, duration_ms
                        FROM document
                        WHERE knowledge_base_id = ?
                        ORDER BY created_at DESC, name, id
                        """,
                documentMapper(), knowledgeBaseId);
    }

    public Set<String> documentIdsForKnowledgeBases(Collection<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(
                queries.documentIdsForKnowledgeBases(List.copyOf(knowledgeBaseIds)));
    }

    @Transactional
    public void save(DocumentInfo info, List<Chunk> chunks) {
        doSave(info, chunks, null, null, info.durationMs());
    }

    @Transactional
    public void save(DocumentInfo info, List<Chunk> chunks,
                     String mediaPath, long durationMs) {
        doSave(info, chunks, null, mediaPath, durationMs);
    }

    @Transactional
    public void save(DocumentInfo info, List<Chunk> chunks, String sourcePath,
                     String mediaPath, long durationMs) {
        doSave(info, chunks, sourcePath, mediaPath, durationMs);
    }

    private void doSave(DocumentInfo info, List<Chunk> chunks,
                        String sourcePath, String mediaPath, long durationMs) {
        if (info.knowledgeBaseId() != null && getKnowledgeBase(info.knowledgeBaseId()) == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        jdbc.update("""
                        INSERT INTO document(
                            id, knowledge_base_id, name, pages, chunk_count, size_bytes, created_at,
                            source_path, media_path, duration_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                info.id(),
                info.knowledgeBaseId(),
                info.name(),
                info.pages(),
                info.chunks(),
                info.sizeBytes(),
                info.createdAt(),
                sourcePath,
                mediaPath,
                durationMs);
        insertChunks(chunks);
        List<Chunk> withVectors = chunks.stream()
                .filter(chunk -> chunk.vector() != null)
                .toList();
        if (!withVectors.isEmpty()) {
            updateVectors(withVectors);
        }
        if (info.knowledgeBaseId() != null) {
            jdbc.update("UPDATE knowledge_base SET updated_at = ? WHERE id = ?",
                    info.createdAt(), info.knowledgeBaseId());
        }
    }

    @Transactional
    public DocumentInfo replaceContent(String docId, List<Chunk> chunks,
                                       long sizeBytes, int pages) {
        DocumentInfo existing = document(docId);
        if (existing == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        jdbc.update("""
                DELETE FROM chunk_vector
                WHERE chunk_key IN (SELECT rowid FROM chunk WHERE document_id = ?)
                """, docId);
        jdbc.update("DELETE FROM chunk WHERE document_id = ?", docId);
        jdbc.update("""
                UPDATE document
                SET pages = ?, chunk_count = ?, size_bytes = ?
                WHERE id = ?
                """, pages, chunks.size(), sizeBytes, docId);
        insertChunks(chunks);
        if (existing.knowledgeBaseId() != null) {
            jdbc.update("UPDATE knowledge_base SET updated_at = ? WHERE id = ?",
                    System.currentTimeMillis(), existing.knowledgeBaseId());
        }
        return document(docId);
    }

    private void insertChunks(List<Chunk> chunks) {
        jdbc.batchUpdate("""
                        INSERT INTO chunk(
                            id, document_id, chunk_order, doc_name, heading, text, location, search_terms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index)
                            throws SQLException {
                        Chunk chunk = chunks.get(index);
                        statement.setString(1, chunk.id());
                        statement.setString(2, chunk.docId());
                        statement.setInt(3, index);
                        statement.setString(4, chunk.docName());
                        statement.setString(5, chunk.heading());
                        statement.setString(6, chunk.text());
                        statement.setString(7, chunk.location());
                        statement.setString(8, buildSearchTerms(chunk.heading(), chunk.text()));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
    }

    public List<DocumentInfo> list() {
        return jdbc.query("""
                        SELECT id, name, knowledge_base_id, pages, chunk_count, size_bytes, created_at,
                               (media_path IS NOT NULL) AS has_media, duration_ms
                        FROM document
                        ORDER BY created_at DESC, name, id
                        """,
                documentMapper());
    }

    public DocumentInfo document(String docId) {
        return jdbc.query("""
                        SELECT id, name, knowledge_base_id, pages, chunk_count, size_bytes, created_at,
                               (media_path IS NOT NULL) AS has_media, duration_ms
                        FROM document
                        WHERE id = ?
                        """,
                documentMapper(), docId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<Chunk> chunks(String docId) {
        if (docId == null || docId.isBlank()) {
            return jdbc.query("""
                    SELECT id, document_id, doc_name, heading, text, location
                    FROM chunk
                    ORDER BY document_id, chunk_order
                    """, chunkMapper());
        }
        return jdbc.query("""
                        SELECT id, document_id, doc_name, heading, text, location
                        FROM chunk
                        WHERE document_id = ?
                        ORDER BY chunk_order
                        """,
                chunkMapper(), docId);
    }

    public List<Chunk> chunksForDocuments(Collection<String> docIds) {
        return toChunks(queries.chunksForDocuments(scopeIds(docIds)));
    }

    public List<Chunk> chunksWithoutVectors(Collection<String> docIds) {
        return chunksWithoutVectors(docIds, -1);
    }

    public List<Chunk> chunksWithoutVectors(Collection<String> docIds, int limit) {
        return toChunks(queries.chunksWithoutVectors(
                scopeIds(docIds), limit > 0 ? limit : null));
    }

    public boolean hasChunks(Collection<String> docIds) {
        return queries.countChunks(scopeIds(docIds)) > 0;
    }

    @Transactional
    public void remove(String docId) {
        String knowledgeBaseId = jdbc.query(
                "SELECT knowledge_base_id FROM document WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                docId);
        jdbc.update("""
                DELETE FROM chunk_vector
                WHERE chunk_key IN (SELECT rowid FROM chunk WHERE document_id = ?)
                """, docId);
        jdbc.update("DELETE FROM document WHERE id = ?", docId);
        if (knowledgeBaseId != null) {
            jdbc.update("UPDATE knowledge_base SET updated_at = ? WHERE id = ?",
                    System.currentTimeMillis(), knowledgeBaseId);
        }
    }

    public String mediaPath(String docId) {
        return jdbc.query(
                "SELECT media_path FROM document WHERE id = ? AND media_path IS NOT NULL",
                rs -> rs.next() ? rs.getString(1) : null,
                docId);
    }

    public List<String> mediaPathsForKnowledgeBase(String knowledgeBaseId) {
        return jdbc.queryForList("""
                SELECT media_path FROM document
                WHERE knowledge_base_id = ? AND media_path IS NOT NULL
                """, String.class, knowledgeBaseId);
    }

    public String sourcePath(String docId) {
        return jdbc.query(
                "SELECT source_path FROM document WHERE id = ? AND source_path IS NOT NULL",
                rs -> rs.next() ? rs.getString(1) : null,
                docId);
    }

    public List<String> sourcePathsForKnowledgeBase(String knowledgeBaseId) {
        return jdbc.queryForList("""
                SELECT source_path FROM document
                WHERE knowledge_base_id = ? AND source_path IS NOT NULL
                """, String.class, knowledgeBaseId);
    }

    public int documentCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM document", Long.class);
        return count == null ? 0 : count.intValue();
    }

    public int totalChunks() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chunk", Long.class);
        return count == null ? 0 : count.intValue();
    }

    public int countVectors() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chunk_vector", Long.class);
        return count == null ? 0 : count.intValue();
    }

    /** 向量写入 chunk_vector，不在 chunk 表或 Java 缓存中保存副本。 */
    @Transactional
    public void updateVectors(List<Chunk> withVectors) {
        List<Chunk> vectors = withVectors.stream()
                .filter(chunk -> chunk.vector() != null)
                .toList();
        if (vectors.isEmpty()) {
            return;
        }
        Map<String, Long> rowIds = rowIds(vectors.stream().map(Chunk::id).toList());
        List<Chunk> valid = vectors.stream()
                .filter(chunk -> rowIds.containsKey(chunk.id()))
                .toList();
        if (valid.isEmpty()) {
            return;
        }
        // SQLite 默认变量上限约 32766：一次性 IN 掉几万个 chunk 会抛
        // "too many SQL variables" 并让整个事务回滚，必须按批拆分。
        final int batchSize = 400;
        for (int offset = 0; offset < valid.size(); offset += batchSize) {
            List<Chunk> batch = valid.subList(offset, Math.min(valid.size(), offset + batchSize));
            List<Long> chunkKeys = batch.stream().map(chunk -> rowIds.get(chunk.id())).toList();
            jdbc.update("DELETE FROM chunk_vector WHERE chunk_key IN ("
                    + placeholders(chunkKeys.size()) + ")", chunkKeys.toArray());
            jdbc.batchUpdate(
                    "INSERT INTO chunk_vector(document_id, chunk_key, embedding) VALUES (?, ?, vec_f32(?))",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index)
                                throws SQLException {
                            Chunk chunk = batch.get(index);
                            statement.setString(1, chunk.docId());
                            statement.setLong(2, rowIds.get(chunk.id()));
                            statement.setBytes(3, toVectorBlob(chunk.vector()));
                        }

                        @Override
                        public int getBatchSize() {
                            return batch.size();
                        }
                    });
        }
    }

    public List<VectorCandidate> vectorCandidates(
            double[] queryVector, Collection<String> docIds, int limit) {
        return queries.vectorCandidates(
                        toVectorBlob(queryVector), Math.max(1, limit), scopeIds(docIds))
                .stream()
                .map(row -> new VectorCandidate(toChunk(row), row.getDistance()))
                .toList();
    }

    public List<KeywordCandidate> keywordCandidates(
            Set<String> keywords, Collection<String> docIds, int limit) {
        List<String> terms = normalizeKeywords(keywords);
        if (terms.isEmpty()) {
            return List.of();
        }
        String ftsQuery = terms.stream()
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .reduce((left, right) -> left + " OR " + right)
                .orElse("");
        int topK = Math.max(1, limit);
        List<String> scope = scopeIds(docIds);
        double keywordDivisor = terms.size() * 2.0;
        // FTS 与 LIKE 必须并查：多词查询里 FTS 只命中一部分词时，单靠 LIKE 才能
        // 命中的文档不能被丢掉。按 chunk id 合并，分数取较大者。
        Map<String, KeywordCandidate> merged = new LinkedHashMap<>();
        for (KeywordCandidate candidate : toKeywordCandidates(queries.ftsCandidates(
                terms, ftsQuery, keywordDivisor, terms.size(), scope, topK))) {
            merged.putIfAbsent(candidate.chunk().id(), candidate);
        }
        for (KeywordCandidate candidate : toKeywordCandidates(queries.likeCandidates(
                terms, keywordDivisor, terms.size(), scope, topK))) {
            merged.merge(candidate.chunk().id(), candidate, (left, right) ->
                    new KeywordCandidate(left.chunk(),
                            Math.max(left.keywordScore(), right.keywordScore()),
                            Math.max(left.headingScore(), right.headingScore())));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(KeywordCandidate::keywordScore).reversed()
                        .thenComparing(Comparator.comparingDouble(KeywordCandidate::headingScore).reversed()))
                .limit(topK)
                .toList();
    }

    private static List<String> scopeIds(Collection<String> docIds) {
        return docIds == null ? null : List.copyOf(docIds);
    }

    private static List<Chunk> toChunks(List<ChunkRow> rows) {
        return rows.stream().map(DocumentStore::toChunk).toList();
    }

    private static Chunk toChunk(ChunkRow row) {
        return new Chunk(row.getId(), row.getDocumentId(), row.getDocName(),
                row.getHeading(), row.getText(), row.getLocation(), null);
    }

    private static List<KeywordCandidate> toKeywordCandidates(List<ChunkRow> rows) {
        return rows.stream()
                .map(row -> new KeywordCandidate(
                        toChunk(row), row.getKeywordScore(), row.getHeadingScore()))
                .toList();
    }

    private List<String> normalizeKeywords(Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return keywords.stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private Map<String, Long> rowIds(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = List.copyOf(chunkIds);
        Map<String, Long> result = new HashMap<>(ids.size());
        final int batchSize = 400;
        for (int offset = 0; offset < ids.size(); offset += batchSize) {
            List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + batchSize));
            jdbc.query("SELECT id, rowid FROM chunk WHERE id IN (" + placeholders(batch.size()) + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            result.put(rs.getString("id"), rs.getLong("rowid")),
                    batch.toArray());
        }
        return result;
    }

    private RowMapper<KnowledgeBase> knowledgeBaseMapper() {
        return (rs, rowNum) -> new KnowledgeBase(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("document_count"),
                rs.getInt("chunk_count"),
                rs.getString("source_dir"));
    }

    private RowMapper<DocumentInfo> documentMapper() {
        return (rs, rowNum) -> new DocumentInfo(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("knowledge_base_id"),
                rs.getInt("pages"),
                rs.getInt("chunk_count"),
                rs.getLong("size_bytes"),
                rs.getLong("created_at"),
                rs.getBoolean("has_media"),
                rs.getLong("duration_ms"));
    }

    private RowMapper<Chunk> chunkMapper() {
        return (rs, rowNum) -> new Chunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("doc_name"),
                rs.getString("heading"),
                rs.getString("text"),
                rs.getString("location"),
                null);
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String buildSearchTerms(String heading, String text) {
        Set<String> terms = new LinkedHashSet<>();
        collectTerms(heading, terms);
        collectTerms(text, terms);
        return String.join(" ", terms);
    }

    /** 入库与查询必须共用同一套分词：否则索引里有英文/数字词，查询却永远提取不到。 */
    public static void collectTerms(String source, Set<String> terms) {
        if (source == null || source.isBlank()) {
            return;
        }
        String value = source.toLowerCase(Locale.ROOT);
        Matcher han = HAN_RUN.matcher(value);
        while (han.find()) {
            String run = han.group();
            if (run.length() == 1) {
                terms.add(run);
                continue;
            }
            for (int index = 0; index + 2 <= run.length(); index++) {
                terms.add(run.substring(index, index + 2));
            }
        }
        Matcher word = WORD.matcher(value);
        while (word.find()) {
            String token = word.group();
            if (!HAN_RUN.matcher(token).matches()) {
                terms.add(token);
            }
        }
    }

    private byte[] toVectorBlob(double[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("向量包含非有限数值");
            }
            buffer.putFloat((float) value);
        }
        return buffer.array();
    }
}
