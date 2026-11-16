package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private record Scope(String sql, List<Object> args) {
    }

    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");

    private static final String KNOWLEDGE_BASE_SELECT = """
            SELECT kb.id, kb.name, kb.description, kb.created_at,
                   (SELECT COUNT(*) FROM document d
                    WHERE d.knowledge_base_id = kb.id) AS document_count,
                   (SELECT COALESCE(SUM(d.chunk_count), 0) FROM document d
                    WHERE d.knowledge_base_id = kb.id) AS chunk_count
            FROM knowledge_base kb
            """;

    private final JdbcTemplate jdbc;

    public DocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                            INSERT INTO knowledge_base(id, name, name_key, description, created_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    id,
                    normalizedName,
                    normalizedName.toLowerCase(Locale.ROOT),
                    description == null ? "" : description.trim(),
                    System.currentTimeMillis());
        } catch (DataIntegrityViolationException error) {
            throw new IllegalArgumentException("同名知识库已存在");
        }
        return getKnowledgeBase(id);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return jdbc.query(KNOWLEDGE_BASE_SELECT + " ORDER BY kb.created_at, kb.id",
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
                        SELECT id, name, knowledge_base_id, pages, chunk_count, size_bytes
                        FROM document
                        WHERE knowledge_base_id = ?
                        ORDER BY name, id
                        """,
                documentMapper(), knowledgeBaseId);
    }

    public Set<String> documentIdsForKnowledgeBases(Collection<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = List.copyOf(knowledgeBaseIds);
        String placeholders = placeholders(ids.size());
        return new LinkedHashSet<>(jdbc.query(
                "SELECT id FROM document WHERE knowledge_base_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getString("id"),
                ids.toArray()));
    }

    @Transactional
    public void save(DocumentInfo info, List<Chunk> chunks) {
        if (info.knowledgeBaseId() != null && getKnowledgeBase(info.knowledgeBaseId()) == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        jdbc.update("""
                        INSERT INTO document(
                            id, knowledge_base_id, name, pages, chunk_count, size_bytes)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                info.id(),
                info.knowledgeBaseId(),
                info.name(),
                info.pages(),
                info.chunks(),
                info.sizeBytes());
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            jdbc.update("""
                            INSERT INTO chunk(
                                id, document_id, chunk_order, doc_name, heading, text, location, search_terms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    chunk.id(),
                    chunk.docId(),
                    index,
                    chunk.docName(),
                    chunk.heading(),
                    chunk.text(),
                    chunk.location(),
                    buildSearchTerms(chunk.heading(), chunk.text()));
            if (chunk.vector() != null) {
                replaceVector(rowId(chunk.id()), chunk.vector());
            }
        }
    }

    public List<DocumentInfo> list() {
        return jdbc.query("""
                        SELECT id, name, knowledge_base_id, pages, chunk_count, size_bytes
                        FROM document
                        ORDER BY name, id
                        """,
                documentMapper());
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
        Scope scope = scope(docIds);
        return jdbc.query("""
                        SELECT c.id, c.document_id, c.doc_name, c.heading, c.text, c.location
                        FROM chunk c
                        WHERE %s
                        ORDER BY c.document_id, c.chunk_order
                        """.formatted(scope.sql()),
                chunkMapper(),
                scope.args().toArray());
    }

    public List<Chunk> chunksWithoutVectors(Collection<String> docIds) {
        Scope scope = scope(docIds);
        return jdbc.query("""
                        SELECT c.id, c.document_id, c.doc_name, c.heading, c.text, c.location
                        FROM chunk c
                        LEFT JOIN chunk_vector v ON v.chunk_key = c.rowid
                        WHERE v.chunk_key IS NULL AND %s
                        ORDER BY c.document_id, c.chunk_order
                        """.formatted(scope.sql()),
                chunkMapper(),
                scope.args().toArray());
    }

    public boolean hasChunks(Collection<String> docIds) {
        Scope scope = scope(docIds);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chunk c WHERE " + scope.sql(),
                Long.class, scope.args().toArray());
        return count != null && count > 0;
    }

    @Transactional
    public void remove(String docId) {
        jdbc.update("""
                DELETE FROM chunk_vector
                WHERE chunk_key IN (SELECT rowid FROM chunk WHERE document_id = ?)
                """, docId);
        jdbc.update("DELETE FROM document WHERE id = ?", docId);
    }

    public int totalChunks() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM chunk", Long.class);
        return count == null ? 0 : count.intValue();
    }

    /** 向量写入 chunk_vector，不在 chunk 表或 Java 缓存中保存副本。 */
    @Transactional
    public void updateVectors(List<Chunk> withVectors) {
        for (Chunk chunk : withVectors) {
            if (chunk.vector() != null) {
                replaceVector(rowId(chunk.id()), chunk.vector());
            }
        }
    }

    public List<VectorCandidate> vectorCandidates(
            double[] queryVector, Collection<String> docIds, int limit) {
        Scope scope = scope(docIds);
        String vectorLiteral = "vec_f32('" + toVectorJson(queryVector) + "')";
        return jdbc.query("""
                        SELECT c.id, c.document_id, c.doc_name, c.heading, c.text, c.location,
                               v.distance
                        FROM chunk_vector v
                        JOIN chunk c ON v.chunk_key = c.rowid
                        WHERE v.embedding MATCH %s AND k = %d AND %s
                        ORDER BY v.distance ASC
                        """.formatted(vectorLiteral, Math.max(1, limit), scope.sql()),
                (rs, rowNum) -> new VectorCandidate(
                        chunkMapper().mapRow(rs, rowNum),
                        rs.getDouble("distance")),
                scope.args().toArray());
    }

    public List<KeywordCandidate> keywordCandidates(
            Set<String> keywords, Collection<String> docIds, int limit) {
        List<String> terms = normalizeKeywords(keywords);
        if (terms.isEmpty()) {
            return List.of();
        }
        Scope scope = scope(docIds);
        String ftsQuery = terms.stream()
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .reduce((left, right) -> left + " OR " + right)
                .orElse("");
        String keywordExpression = keywordExpression(terms);
        String headingExpression = headingExpression(terms);

        List<Object> args = new ArrayList<>();
        args.addAll(textExpressionArgs(terms));
        args.addAll(singleExpressionArgs(terms));
        args.add(ftsQuery);
        args.addAll(scope.args());
        args.add(Math.max(1, limit));

        List<KeywordCandidate> candidates = jdbc.query("""
                        SELECT c.id, c.document_id, c.doc_name, c.heading, c.text, c.location,
                               %s AS keyword_score,
                               %s AS heading_score
                        FROM chunk c
                        JOIN chunk_fts ON chunk_fts.rowid = c.rowid
                        WHERE chunk_fts MATCH ? AND %s
                        ORDER BY keyword_score DESC, heading_score DESC
                        LIMIT ?
                        """.formatted(keywordExpression, headingExpression, scope.sql()),
                keywordCandidateMapper(),
                args.toArray());
        return candidates.isEmpty()
                ? fallbackKeywordCandidates(terms, scope, limit, keywordExpression, headingExpression)
                : candidates;
    }

    private List<KeywordCandidate> fallbackKeywordCandidates(
            List<String> terms, Scope scope, int limit,
            String keywordExpression, String headingExpression) {
        String likeClause = String.join(" OR ",
                Collections.nCopies(terms.size(),
                        "(LOWER(c.text) LIKE '%' || LOWER(?) || '%' "
                                + "OR LOWER(c.heading) LIKE '%' || LOWER(?) || '%')"));
        List<Object> args = new ArrayList<>();
        args.addAll(textExpressionArgs(terms));
        args.addAll(singleExpressionArgs(terms));
        for (String term : terms) {
            args.add(term);
            args.add(term);
        }
        args.addAll(scope.args());
        args.add(Math.max(1, limit));
        return jdbc.query("""
                        SELECT c.id, c.document_id, c.doc_name, c.heading, c.text, c.location,
                               %s AS keyword_score,
                               %s AS heading_score
                        FROM chunk c
                        WHERE (%s) AND %s
                        ORDER BY keyword_score DESC, heading_score DESC
                        LIMIT ?
                        """.formatted(
                        keywordExpression, headingExpression, likeClause, scope.sql()),
                keywordCandidateMapper(),
                args.toArray());
    }

    private String keywordExpression(List<String> terms) {
        String sum = terms.stream()
                .map(term -> """
                        MIN(2.0, (
                            LENGTH(LOWER(c.text)) - LENGTH(REPLACE(LOWER(c.text), LOWER(?), ''))
                        ) * 1.0 / MAX(LENGTH(?), 1))""")
                .reduce((left, right) -> left + " + " + right)
                .orElse("0");
        return "MIN(1.0, (" + sum + ") / " + (terms.size() * 2.0) + ")";
    }

    private String headingExpression(List<String> terms) {
        String sum = terms.stream()
                .map(term -> "CASE WHEN INSTR(LOWER(COALESCE(c.heading, '')), LOWER(?)) > 0 "
                        + "THEN 1 ELSE 0 END")
                .reduce((left, right) -> left + " + " + right)
                .orElse("0");
        return "MIN(1.0, (" + sum + ") * 1.0 / " + terms.size() + ")";
    }

    private List<Object> textExpressionArgs(List<String> terms) {
        List<Object> args = new ArrayList<>(terms.size() * 2);
        for (String term : terms) {
            args.add(term);
            args.add(term);
        }
        return args;
    }

    private List<Object> singleExpressionArgs(List<String> terms) {
        return new ArrayList<>(terms);
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

    private void replaceVector(long chunkKey, double[] vector) {
        jdbc.update("DELETE FROM chunk_vector WHERE chunk_key = ?", chunkKey);
        jdbc.update("INSERT INTO chunk_vector(chunk_key, embedding) VALUES (?, vec_f32(?))",
                chunkKey, toVectorBlob(vector));
    }

    private long rowId(String chunkId) {
        Long rowId = jdbc.queryForObject("SELECT rowid FROM chunk WHERE id = ?", Long.class, chunkId);
        if (rowId == null) {
            throw new IllegalArgumentException("切片不存在: " + chunkId);
        }
        return rowId;
    }

    private RowMapper<KnowledgeBase> knowledgeBaseMapper() {
        return (rs, rowNum) -> new KnowledgeBase(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("created_at"),
                rs.getInt("document_count"),
                rs.getInt("chunk_count"));
    }

    private RowMapper<DocumentInfo> documentMapper() {
        return (rs, rowNum) -> new DocumentInfo(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("knowledge_base_id"),
                rs.getInt("pages"),
                rs.getInt("chunk_count"),
                rs.getLong("size_bytes"));
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

    private RowMapper<KeywordCandidate> keywordCandidateMapper() {
        return (rs, rowNum) -> new KeywordCandidate(
                chunkMapper().mapRow(rs, rowNum),
                rs.getDouble("keyword_score"),
                rs.getDouble("heading_score"));
    }

    private Scope scope(Collection<String> docIds) {
        if (docIds == null) {
            return new Scope("1 = 1", List.of());
        }
        if (docIds.isEmpty()) {
            return new Scope("1 = 0", List.of());
        }
        List<String> ids = List.copyOf(docIds);
        return new Scope("c.document_id IN (" + placeholders(ids.size()) + ")",
                new ArrayList<>(ids));
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

    private void collectTerms(String source, Set<String> terms) {
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

    private String toVectorJson(double[] vector) {
        StringBuilder json = new StringBuilder(vector.length * 12);
        json.append('[');
        for (int index = 0; index < vector.length; index++) {
            double value = vector[index];
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("向量包含非有限数值");
            }
            if (index > 0) {
                json.append(',');
            }
            json.append(Double.toString(value));
        }
        return json.append(']').toString();
    }
}
