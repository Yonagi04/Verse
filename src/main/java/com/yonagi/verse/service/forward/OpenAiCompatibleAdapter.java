package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容协议适配实现 — 用 Spring {@link RestClient} 同步调用上游
 * {@code {apiUrl}/chat/completions}，注入真实 apiKey 并替换 model 为上游 model_name。
 *
 * @author Yonagi
 */
@Slf4j
@Component
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private final RestClient restClient;

    public OpenAiCompatibleAdapter(
            @Value("${verse.llm.upstream.connect-timeout:5000}") int connectTimeout,
            @Value("${verse.llm.upstream.read-timeout:120000}") int readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public String forward(ForwardContext ctx) {
        String url = buildChatCompletionsUrl(ctx.getApiUrl());
        String body = replaceModel(ctx.getBody(), ctx.getModelName());
        try {
            return restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            String upstreamMsg = extractUpstreamError(e.getResponseBodyAsString());
            log.warn("[llm-forward] 上游返回错误: status={}, message={}", e.getStatusCode().value(), upstreamMsg);
            throw new ClientException(upstreamMsg, LlmForwardErrorCodeEnum.UPSTREAM_ERROR);
        } catch (ResourceAccessException e) {
            log.warn("[llm-forward] 上游连接失败或超时: url={}", url, e);
            throw new ClientException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                    LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT);
        }
    }

    /**
     * 拼接 chat/completions 端点，兼容 apiUrl 带不带末尾斜杠。
     */
    private String buildChatCompletionsUrl(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /**
     * 将请求体中的 model 字段替换为上游 model_name，其余字段原样透传。
     */
    private String replaceModel(String body, String modelName) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return body;
        }
        json.put("model", modelName);
        return JSON.toJSONString(json);
    }

    /**
     * 从上游错误体中提取 message，尽量保持 OpenAI 风格，提取失败则退回原始错误体。
     */
    private String extractUpstreamError(String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            return LlmForwardErrorCodeEnum.UPSTREAM_ERROR.message();
        }
        try {
            JSONObject json = JSON.parseObject(rawBody);
            JSONObject error = json == null ? null : json.getJSONObject("error");
            if (error != null && StringUtils.hasText(error.getString("message"))) {
                return error.getString("message");
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，直接透传
        }
        return rawBody;
    }
}
