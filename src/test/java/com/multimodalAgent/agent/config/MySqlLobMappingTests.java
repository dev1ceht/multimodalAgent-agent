package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.domain.KnowledgeDocument;
import com.multimodalAgent.agent.domain.KnowledgeVersionChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersionDocument;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlLobMappingTests {

    @Test
    void stringLobsMatchTheLongtextColumnsCreatedByFlyway() {
        List<Class<?>> entitiesWithLongtextColumns = List.of(
                ChatMessage.class,
                PsychologicalReport.class,
                KnowledgeDocument.class,
                KnowledgeVersionDocument.class,
                KnowledgeChunk.class,
                KnowledgeVersionChunk.class);

        entitiesWithLongtextColumns.stream()
                .flatMap(entity -> List.of(entity.getDeclaredFields()).stream())
                .filter(field -> field.isAnnotationPresent(Lob.class))
                .forEach(this::assertLongtextMapping);
    }

    private void assertLongtextMapping(Field field) {
        Column column = field.getAnnotation(Column.class);

        assertThat(column)
                .as("%s.%s must declare its Flyway LONGTEXT type", field.getDeclaringClass().getSimpleName(), field.getName())
                .isNotNull();
        assertThat(column.columnDefinition())
                .as("%s.%s column definition", field.getDeclaringClass().getSimpleName(), field.getName())
                .isEqualToIgnoringCase("LONGTEXT");
    }
}
