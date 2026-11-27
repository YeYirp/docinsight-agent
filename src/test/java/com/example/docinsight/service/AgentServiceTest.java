package com.example.docinsight.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    @Test
    void keywordsKeepEnglishNumbersAndAllHanBigrams() {
        Set<String> words = AgentService.keywords("grafana 告警 RBAC 2024 配置备份策略说明");
        assertThat(words).contains("grafana", "rbac", "2024", "告警", "配置", "备份", "策略");
    }

    @Test
    void keywordsAreNotTruncatedToSix() {
        Set<String> words = AgentService.keywords("甲乙丙丁戊己庚辛壬癸子丑寅卯");
        assertThat(words.size()).isGreaterThan(6);
    }

    @Test
    void sanitizeCitationsDropsOutOfRangeNumbers() {
        String answer = "按资料[1]执行，另有说法[9]和[abc]，以及[2]。";
        assertThat(AgentService.sanitizeCitations(answer, 2))
                .isEqualTo("按资料[1]执行，另有说法和，以及[2]。");
    }

    @Test
    void citationGuardHandlesSplitBrackets() {
        AgentService.CitationGuard guard = new AgentService.CitationGuard(2);
        assertThat(guard.feed("结论[")).isEqualTo("结论");
        assertThat(guard.feed("1] 有效，")).isEqualTo("[1] 有效，");
        assertThat(guard.feed("再看[9]无效")).isEqualTo("再看无效");
    }

    @Test
    void noHitMessageIsStable() {
        assertThat(AgentService.NO_HIT_MESSAGE).contains("没有检索到相关内容");
    }
}
