package com.multimodalAgent.agent.service.ai;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * 模型调用统一接口。
 *
 * <p>业务层通过 complete 做分类/评估，通过 stream 做学生端流式回答。</p>
 */
public interface AiClient {

    String complete(List<AiMessage> messages);

    /**
     * 请求严格遵循 JSON Schema 的结构化结果。
     *
     * <p>不支持原生结构化输出的适配器可以退回普通补全，业务层仍会执行服务端校验。</p>
     */
    default String completeJson(List<AiMessage> messages, Map<String, Object> schema) {
        return complete(messages);
    }

    Flux<String> stream(List<AiMessage> messages);
}
