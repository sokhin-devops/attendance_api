package com.attendance.api.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "attendance-api",
                "status", "ok",
                "docs", "/swagger-ui.html",
                "health", "/actuator/health"
        );
    }
}
