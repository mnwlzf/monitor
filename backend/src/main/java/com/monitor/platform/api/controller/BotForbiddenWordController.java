package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotForbiddenWordRequest;
import com.monitor.platform.api.dto.BotForbiddenWordResponse;
import com.monitor.platform.bot.BotForbiddenWordService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * QQ 机器人违禁词维护接口。
 *
 * <p>仅 ADMIN 可访问（见 SecurityConfig 的 {@code /api/v1/settings/**} 规则）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/bot/forbidden-words")
public class BotForbiddenWordController {

    private final BotForbiddenWordService service;

    public BotForbiddenWordController(BotForbiddenWordService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<BotForbiddenWordResponse>> list() {
        return ApiResponse.of(service.list().stream().map(BotForbiddenWordResponse::from).toList(), null);
    }

    @PostMapping
    public ApiResponse<BotForbiddenWordResponse> add(@RequestBody BotForbiddenWordRequest request) {
        return ApiResponse.of(BotForbiddenWordResponse.from(service.add(request.word())), null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.of(null, null);
    }
}
