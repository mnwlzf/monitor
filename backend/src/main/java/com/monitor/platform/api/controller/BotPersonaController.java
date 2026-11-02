package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotPersonaRequest;
import com.monitor.platform.api.dto.BotPersonaResponse;
import com.monitor.platform.bot.BotPersonaService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** QQ 机器人人设库维护接口，仅 ADMIN 可访问。 */
@RestController
@RequestMapping("/api/v1/settings/bot/personas")
public class BotPersonaController {

    private final BotPersonaService service;

    public BotPersonaController(BotPersonaService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<BotPersonaResponse>> list() {
        return ApiResponse.of(service.list().stream().map(BotPersonaResponse::from).toList(), null);
    }

    @PostMapping
    public ApiResponse<BotPersonaResponse> create(@RequestBody BotPersonaRequest request) {
        return ApiResponse.of(BotPersonaResponse.from(service.create(request)), null);
    }

    @PutMapping("/{id}")
    public ApiResponse<BotPersonaResponse> update(@PathVariable Long id,
                                                  @RequestBody BotPersonaRequest request) {
        return ApiResponse.of(BotPersonaResponse.from(service.update(id, request)), null);
    }

    @PutMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable Long id) {
        service.setDefault(id);
        return ApiResponse.of(null, null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.of(null, null);
    }
}
