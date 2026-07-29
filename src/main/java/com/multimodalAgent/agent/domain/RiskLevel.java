package com.multimodalAgent.agent.domain;

/**
 * 请求安全处置等级。NONE 表示没有现实心理困扰或风险信号，不代表不能使用 RAG。
 */
public enum RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}
