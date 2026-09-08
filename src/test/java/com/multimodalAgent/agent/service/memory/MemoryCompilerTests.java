package com.multimodalAgent.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.service.ai.AiClient;
import org.junit.jupiter.api.Test;

class MemoryCompilerTests {

    @Test
    void compilesAtomicFactsTopicsAndOnlySupportedRelations() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.completeJson(any(), any())).thenReturn("""
                {
                  "facts":[
                    {"content":"答辩安排在周五。","occurredAt":"2026-09-11T09:00:00Z"},
                    {"content":"周三需要复查数据。","occurredAt":"2026-09-09T09:00:00Z"}
                  ],
                  "topics":[{"key":"graduation-defense","title":"毕业答辩","summary":"答辩安排与准备事项"}],
                  "relations":[
                    {"source":"new:1","target":"new:0","type":"CAUSES","confidence":0.91},
                    {"source":"new:0","target":"new:1","type":"UNSUPPORTED","confidence":1.0}
                  ],
                  "memberships":[
                    {"fact":"new:0","topicKey":"graduation-defense"},
                    {"fact":"new:1","topicKey":"graduation-defense"}
                  ]
                }
                """);
        MemoryCompiler compiler = new MemoryCompiler(aiClient, new ObjectMapper());

        CompiledMemory result = compiler.compile(new MemoryCompilationInput(
                11L,
                22L,
                101L,
                "我把答辩改到了周五，因为周三还要复查数据。",
                "无既有事实",
                "2026-09-07T08:00:00Z"));

        assertThat(result.facts()).extracting(CompiledFact::content)
                .containsExactly("答辩安排在周五。", "周三需要复查数据。");
        assertThat(result.topics()).extracting(CompiledTopic::key)
                .containsExactly("graduation-defense");
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).type()).isEqualTo(MemoryRelationType.CAUSES);
        assertThat(result.memberships()).hasSize(2);
    }
}
