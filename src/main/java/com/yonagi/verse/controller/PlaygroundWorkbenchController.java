package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.service.PlaygroundWorkbenchService;
import com.yonagi.verse.service.impl.PlaygroundConfiguration;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;

/** 二期成员私有工作台；客户端不提供任意历史、上游地址或密钥。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/{tenantId}/playground/workbench")
public class PlaygroundWorkbenchController {
    private final PlaygroundWorkbenchService service;

    @GetMapping("/models")
    public Result<List<JSONObject>> models(@PathVariable Long tenantId) { return Results.success(service.models(UserContextHolder.get(), tenantId)); }

    @GetMapping("/{kind:groups|presets}")
    public Result<List<JSONObject>> list(@PathVariable Long tenantId, @PathVariable String kind, @RequestParam(required = false) String keyword) {
        return Results.success(service.list(UserContextHolder.get(), tenantId, type(kind), keyword));
    }
    @PostMapping("/{kind:groups|presets}")
    public Result<JSONObject> create(@PathVariable Long tenantId, @PathVariable String kind, @RequestBody JSONObject body) {
        return Results.success(service.create(UserContextHolder.get(), tenantId, type(kind), body));
    }
    @GetMapping("/{kind:groups|presets}/{id}")
    public Result<JSONObject> detail(@PathVariable Long tenantId, @PathVariable String kind, @PathVariable Long id) {
        JSONObject result = service.detail(UserContextHolder.get(), tenantId, id); requireKind(kind, result); return Results.success(result);
    }
    @PutMapping("/{kind:groups|presets}/{id}")
    public Result<JSONObject> update(@PathVariable Long tenantId, @PathVariable String kind, @PathVariable Long id, @RequestBody JSONObject body) {
        requireKind(kind, service.detail(UserContextHolder.get(), tenantId, id));
        return Results.success(service.update(UserContextHolder.get(), tenantId, id, body));
    }
    @DeleteMapping("/{kind:groups|presets}/{id}")
    public Result<Boolean> delete(@PathVariable Long tenantId, @PathVariable String kind, @PathVariable Long id) {
        requireKind(kind, service.detail(UserContextHolder.get(), tenantId, id));
        return Results.success(service.delete(UserContextHolder.get(), tenantId, id));
    }
    @PostMapping("/groups/{id}/rounds")
    public Result<JSONObject> round(@PathVariable Long tenantId, @PathVariable Long id, @RequestHeader("Idempotency-Key") String key, @RequestBody JSONObject body) {
        PlaygroundConfiguration.fields(body, "prompt");
        if (!(body.get("prompt") instanceof String)) throw PlaygroundConfiguration.invalid();
        return Results.success(service.round(UserContextHolder.get(), tenantId, id, body.getString("prompt"), key));
    }
    @PostMapping(value = "/attempts/{id}/stream", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter stream(@PathVariable Long tenantId, @PathVariable Long id, HttpServletResponse response) {
        var events = service.stream(UserContextHolder.get(), tenantId, id);
        response.setContentType("text/event-stream;charset=UTF-8"); response.setHeader("Cache-Control", "no-cache");
        return PlaygroundController.emitter(events);
    }
    @PostMapping("/attempts/{id}/stop")
    public Result<Boolean> stop(@PathVariable Long tenantId, @PathVariable Long id) { return Results.success(service.stop(UserContextHolder.get(), tenantId, id)); }
    @PostMapping("/attempts/{id}/retry")
    public Result<JSONObject> retry(@PathVariable Long tenantId, @PathVariable Long id, @RequestHeader("Idempotency-Key") String key) {
        return Results.success(service.retry(UserContextHolder.get(), tenantId, id, key));
    }
    @PostMapping("/groups/{id}/fork")
    public Result<JSONObject> fork(@PathVariable Long tenantId, @PathVariable Long id, @RequestBody JSONObject body) {
        return Results.success(service.fork(UserContextHolder.get(), tenantId, id, body));
    }
    @PostMapping("/presets/{id}/restore")
    public Result<JSONObject> restore(@PathVariable Long tenantId, @PathVariable Long id, @RequestBody JSONObject body) {
        PlaygroundConfiguration.fields(body, "version");
        if (!(body.get("version") instanceof Number n) || n.doubleValue() != n.intValue()) throw PlaygroundConfiguration.invalid();
        return Results.success(service.restore(UserContextHolder.get(), tenantId, id, n.intValue()));
    }
    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<Result<?>> handle(AbstractException error) { return PlaygroundController.failure(error); }
    private String type(String kind) { return kind.equals("groups") ? "GROUP" : "PRESET"; }
    private void requireKind(String kind, JSONObject value) { if (!type(kind).equals(value.getString("kind"))) throw PlaygroundConfiguration.invalid(); }
}
