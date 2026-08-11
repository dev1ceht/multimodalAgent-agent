package com.multimodalAgent.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供给同源前端的最小应用存活检查，不暴露内部 Actuator 健康详情。
 */
@RestController
@RequestMapping("/api")
public class ApplicationHealthController {

    @GetMapping("/health")
    public ApplicationHealthResponse health() {
        return new ApplicationHealthResponse("UP");
    }

    public record ApplicationHealthResponse(String status) {
    }
}
