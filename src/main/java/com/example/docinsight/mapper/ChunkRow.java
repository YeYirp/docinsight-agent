package com.example.docinsight.mapper;

/**
 * chunk 表在检索查询中的投影。
 * 分数列由具体查询决定：向量查询只填 distance，关键词查询填两个分数。
 */
public class ChunkRow {

    private String id;
    private String documentId;
    private String docName;
    private String heading;
    private String text;
    private String location;
    private double distance;
    private double keywordScore;
    private double headingScore;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double getKeywordScore() {
        return keywordScore;
    }

    public void setKeywordScore(double keywordScore) {
        this.keywordScore = keywordScore;
    }

    public double getHeadingScore() {
        return headingScore;
    }

    public void setHeadingScore(double headingScore) {
        this.headingScore = headingScore;
    }
}
