package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * OpenAI 兼容协议适配实现 — 用 Spring {@link RestClient} 同步调用上游
 * {@code {apiUrl}/chat/completions}，注入真实 apiKey 并替换 model 为上游 model_name。
 *
 * @author Yonagi
 */
@Slf4j
@Component
public class OpenAiCompatibleAdapter implements ProviderAdapter, AdapterRegistration {

    @Override
    public com.yonagi.verse.common.enums.ModelOperation operation() {
        return com.yonagi.verse.common.enums.ModelOperation.CHAT_COMPLETIONS;
    }

    @Override
    public com.yonagi.verse.common.enums.UpstreamProtocol protocol() {
        return com.yonagi.verse.common.enums.UpstreamProtocol.OPENAI_COMPAT;
    }

    @Override
    public String provider() {
        return null;
    }

    private final RestClient restClient;

    private final WebClient webClient;

    public OpenAiCompatibleAdapter(
            @Value("${verse.llm.upstream.connect-timeout:5000}") int connectTimeout,
            @Value("${verse.llm.upstream.read-timeout:120000}") int readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
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
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status >= 500;
            String upstreamMsg = extractUpstreamError(e.getResponseBodyAsString());
            log.warn("[llm-forward] 上游返回错误: status={}, message={}", status, upstreamMsg);
            throw new UpstreamFailureException(upstreamMsg, LlmForwardErrorCodeEnum.UPSTREAM_ERROR, retryable);
        } catch (ResourceAccessException e) {
            log.warn("[llm-forward] 上游连接失败或超时: url={}", url, e);
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                    LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true);
        }
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ForwardContext ctx) {
        String url = buildChatCompletionsUrl(ctx.getApiUrl());
        String body = buildStreamBody(ctx.getBody(), ctx.getModelName());
        return webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .onErrorMap(WebClientResponseException.class, e ->
                        toUpstreamFailure(e.getStatusCode().value(), e.getResponseBodyAsString()))
                .onErrorMap(WebClientRequestException.class, e -> {
                    log.warn("[llm-forward] 上游连接失败或超时: url={}", url, e);
                    return new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                            LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true);
                });
    }

    /**
     * 构造流式请求体：替换 model 为上游 model_name，并注入 stream_options.include_usage=true
     * 以获取末端 usage chunk（用于精确结算）。
     */
    private String buildStreamBody(String body, String modelName) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return body;
        }
        json.put("model", modelName);
        json.put("stream", true);
        JSONObject streamOptions = json.getJSONObject("stream_options");
        if (streamOptions == null) {
            streamOptions = new JSONObject();
            json.put("stream_options", streamOptions);
        }
        streamOptions.put("include_usage", true);
        return JSON.toJSONString(json);
    }

    /**
     * 将上游非 2xx 响应映射为 UpstreamFailureException，尽量保留上游错误 message。
     */
    private UpstreamFailureException toUpstreamFailure(int status, String rawBody) {
        boolean retryable = status == 429 || status >= 500;
        String upstreamMsg = extractUpstreamError(rawBody);
        log.warn("[llm-forward] 上游返回错误: status={}, message={}", status, upstreamMsg);
        return new UpstreamFailureException(upstreamMsg, LlmForwardErrorCodeEnum.UPSTREAM_ERROR, retryable);
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
