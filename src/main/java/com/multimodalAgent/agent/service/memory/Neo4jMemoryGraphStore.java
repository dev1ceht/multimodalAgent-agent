package com.multimodalAgent.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class Neo4jMemoryGraphStore implements MemoryGraphStore {
    private final WebClient webClient;

    public Neo4jMemoryGraphStore(multimodalAgentProperties properties, WebClient.Builder builder) {
        var memory = properties.getMemory();
        this.webClient = builder.clone().baseUrl(memory.getNeo4jBaseUrl())
                .defaultHeaders(headers -> headers.setBasicAuth(
                        memory.getNeo4jUsername(), memory.getNeo4jPassword()))
                .build();
    }

    @Override
    public void upsert(MemoryProjectionBatch batch) {
        upsert(batch, () -> {});
    }

    @Override
    public void upsert(MemoryProjectionBatch batch, Runnable leaseGuard) {
        List<Map<String, Object>> statements = List.of(
                statement("UNWIND $rows AS row MERGE (f:Fact {id: row.id}) "
                                + "SET f.userId=row.userId,f.sessionId=row.sessionId,f.content=row.content,f.occurredAt=row.occurredAt",
                        batch.facts().stream().map(f -> Map.<String, Object>of(
                                "id", f.id(), "userId", batch.userId(), "sessionId", f.sessionId(),
                                "content", f.content(), "occurredAt", f.occurredAt().toString())).toList()),
                statement("UNWIND $rows AS row MERGE (t:Topic {id: row.id}) "
                                + "ON CREATE SET t.projectionRevision=-1 WITH t,row "
                                + "WHERE coalesce(t.projectionRevision,-1) <= row.projectionRevision "
                                + "SET t.userId=row.userId,t.key=row.key,t.title=row.title,t.summary=row.summary,"
                                + "t.projectionRevision=row.projectionRevision",
                        batch.topics().stream().map(t -> Map.<String, Object>of(
                                "id", t.id(), "userId", batch.userId(), "key", t.key(),
                                "title", t.title(), "summary", t.summary(),
                                "projectionRevision", t.projectionRevision())).toList()),
                statement("UNWIND $rows AS row MATCH (f:Fact {id:row.factId}),(t:Topic {id:row.topicId}) "
                                + "MERGE (f)-[:BELONGS_TO]->(t)",
                        batch.memberships().stream().map(m -> Map.<String, Object>of(
                                "factId", m.factId(), "topicId", m.topicId())).toList()),
                statement("UNWIND $rows AS row MATCH (a:Fact {id:row.source}),(b:Fact {id:row.target}) "
                                + "MERGE (a)-[r:MEMORY_RELATION {type:row.type}]->(b) SET r.confidence=row.confidence",
                        batch.relations().stream().map(r -> Map.<String, Object>of(
                                "source", r.sourceFactId(), "target", r.targetFactId(),
                                "type", r.type().name(), "confidence", r.confidence())).toList()));
        statements.forEach(statement -> {
            leaseGuard.run();
            execute(statement);
        });
    }

    @Override
    public List<MemoryGraphHit> expand(Long userId, List<Long> seedFactIds, int maxHops) {
        if (seedFactIds.isEmpty()) return List.of();
        int hops = Math.max(1, Math.min(3, maxHops));
        String cypher = "MATCH (seed:Fact) WHERE seed.id IN $seeds AND seed.userId=$userId "
                + "MATCH p=(seed)-[:MEMORY_RELATION*1.." + hops + "]-(fact:Fact {userId:$userId}) "
                + "WHERE all(n IN nodes(p) WHERE n.userId=$userId) "
                + "WITH p,fact,relationships(p) AS rels,length(p) AS depth ORDER BY depth ASC "
                + "RETURN DISTINCT fact.id,fact.content,[n IN nodes(p) | n.id],"
                + "[n IN nodes(p) | n.content],[r IN rels | r.type],"
                + "[r IN rels | startNode(r).id],[r IN rels | endNode(r).id],depth LIMIT 40";
        JsonNode response = execute(Map.of(
                "statement", cypher,
                "parameters", Map.of("seeds", seedFactIds, "userId", userId)));
        JsonNode data = response.path("data").path("values");
        if (!data.isArray()) return List.of();
        List<MemoryGraphHit> hits = new ArrayList<>();
        for (JsonNode row : data) {
            if (row.size() < 8) continue;
            try {
                JsonNode relationTypes = row.path(4);
                if (relationTypes.isEmpty()) continue;
                hits.add(new MemoryGraphHit(row.path(0).asLong(), row.path(1).asText(),
                        MemoryRelationType.valueOf(relationTypes.path(0).asText()), row.path(7).asInt(),
                        pathContext(row.path(2), row.path(3), relationTypes, row.path(5), row.path(6))));
            } catch (IllegalArgumentException ignored) {
                // Only the closed relationship vocabulary is exposed to the recall layer.
            }
        }
        return List.copyOf(hits);
    }

    private String pathContext(JsonNode nodeIds, JsonNode nodeContents, JsonNode relationTypes,
            JsonNode relationSources, JsonNode relationTargets) {
        List<String> segments = new ArrayList<>();
        int edges = Math.min(relationTypes.size(), Math.max(0, nodeIds.size() - 1));
        for (int i = 0; i < edges; i++) {
            long from = nodeIds.path(i).asLong();
            long to = nodeIds.path(i + 1).asLong();
            String left = nodeContents.path(i).asText("fact:" + from);
            String right = nodeContents.path(i + 1).asText("fact:" + to);
            String type = relationTypes.path(i).asText();
            boolean forward = relationSources.path(i).asLong() == from
                    && relationTargets.path(i).asLong() == to;
            segments.add(forward
                    ? left + " -[" + type + "]-> " + right
                    : left + " <-[" + type + "]- " + right);
        }
        return String.join("；", segments);
    }

    private Map<String, Object> statement(String cypher, List<Map<String, Object>> rows) {
        return Map.of("statement", cypher, "parameters", Map.of("rows", rows));
    }

    private JsonNode execute(Map<String, Object> statement) {
        JsonNode response = webClient.post().uri("/db/neo4j/query/v2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(statement)
                .retrieve().bodyToMono(JsonNode.class).block();
        if (response == null) throw new IllegalStateException("Neo4j returned an empty response.");
        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Neo4j query failed: " + errors.path(0).path("message").asText());
        }
        return response;
    }
}
