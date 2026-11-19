package com.example.docinsight.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/** 检索侧的动态 SQL：范围 IN 列表、关键词打分表达式、FTS/LIKE 召回、vec0 KNN。 */
@Mapper
public interface DocumentQueryMapper {

    List<String> documentIdsForKnowledgeBases(@Param("kbIds") Collection<String> knowledgeBaseIds);

    List<ChunkRow> chunksForDocuments(@Param("docIds") Collection<String> docIds);

    List<ChunkRow> chunksWithoutVectors(@Param("docIds") Collection<String> docIds,
                                        @Param("limit") Integer limit);

    long countChunks(@Param("docIds") Collection<String> docIds);

    List<ChunkRow> vectorCandidates(@Param("queryVector") byte[] queryVector,
                                    @Param("k") int k,
                                    @Param("docIds") Collection<String> docIds);

    List<ChunkRow> ftsCandidates(@Param("terms") List<String> terms,
                                 @Param("ftsQuery") String ftsQuery,
                                 @Param("keywordDivisor") double keywordDivisor,
                                 @Param("headingDivisor") int headingDivisor,
                                 @Param("docIds") Collection<String> docIds,
                                 @Param("limit") int limit);

    List<ChunkRow> likeCandidates(@Param("terms") List<String> terms,
                                  @Param("keywordDivisor") double keywordDivisor,
                                  @Param("headingDivisor") int headingDivisor,
                                  @Param("docIds") Collection<String> docIds,
                                  @Param("limit") int limit);
}
