package com.example.docinsight.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reordersVectorsByIndexField() throws Exception {
        JsonNode data = mapper.readTree("""
                [{"index":1,"embedding":[0.4,0.5]},
                 {"index":0,"embedding":[0.1,0.2]}]
                """);
        List<double[]> vectors = EmbeddingClient.parseData(data, 2);
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isEqualTo(0.1);
        assertThat(vectors.get(1)[0]).isEqualTo(0.4);
    }

    @Test
    void acceptsMissingIndexInOrder() throws Exception {
        JsonNode data = mapper.readTree("""
                [{"embedding":[1,2]},{"embedding":[3,4]}]
                """);
        assertThat(EmbeddingClient.parseData(data, 2).get(1)[0]).isEqualTo(3.0);
    }

    @Test
    void rejectsDuplicateOrOutOfRangeIndex() throws Exception {
        JsonNode duplicate = mapper.readTree("""
                [{"index":0,"embedding":[1,2]},{"index":0,"embedding":[3,4]}]
                """);
        assertThat(EmbeddingClient.parseData(duplicate, 2)).isNull();
        JsonNode outOfRange = mapper.readTree("""
                [{"index":9,"embedding":[1,2]}]
                """);
        assertThat(EmbeddingClient.parseData(outOfRange, 1)).isNull();
    }
}
