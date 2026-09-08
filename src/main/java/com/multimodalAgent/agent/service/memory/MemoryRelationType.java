package com.multimodalAgent.agent.service.memory;

/** 事实之间允许进入长期记忆图谱的关系类型。 */
public enum MemoryRelationType {
    CAUSES,
    TEMPORAL_BEFORE,
    TEMPORAL_AFTER,
    SUPPORTS,
    CONTRADICTS,
    ELABORATES,
    SIMILAR_TO,
    CO_OCCURS
}
