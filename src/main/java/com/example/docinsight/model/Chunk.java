package com.example.docinsight.model;

/**
 * 检索的最小单元。location 用于前端溯源（第几页 / 第几段）。
 */
public record Chunk(
        String id,
        String docId,
        String docName,
        String heading,
        String text,
        String location,
        double[] vector
) {
    public Chunk withVector(double[] v) {
        return new Chunk(id, docId, docName, heading, text, location, v);
    }
}
