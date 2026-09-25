package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.nio.charset.StandardCharsets;

/** 明确声明的 OpenAI 兼容 JSON 操作，不从供应商名称推断能力。 */
public class OpenAiJsonOperationAdapter implements ProviderAdapter, AdapterRegistration {
    private final ModelOperation operation;
    private final String path;
    private final RestClient restClient;
    private final WebClient webClient;
    @Value("${verse.llm.media.max-image-json-bytes:8388608}")
    private int maxImageJsonBytes = 8 * 1024 * 1024;

    public OpenAiJsonOperationAdapter(ModelOperation operation, String path) {
        this.operation = operation;
        this.path = path;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.webClient = WebClient.create();
    }

    @Override public ModelOperation operation() { return operation; }
    @Override public UpstreamProtocol protocol() { return UpstreamProtocol.OPENAI_COMPAT; }
    @Override public String provider() { return null; }

    @Override
    public String forward(ForwardContext context) {
        String body = requestBody(context);
        try {
            var request = restClient.post().uri(url(context))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + context.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body);
            if (operation == ModelOperation.EMBEDDINGS) {
                JSONObject input = JSON.parseObject(context.getBody());
                return EmbeddingPayload.normalize(request.retrieve().body(String.class),
                        EmbeddingPayload.validate(input), input.getInteger("dimensions"));
            }
            if (operation != ModelOperation.IMAGE_GENERATION) return request.retrieve().body(String.class);
            return request.exchange((outbound, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    byte[] limited = response.getBody().readNBytes(1024);
                    throw UpstreamErrors.from(response.getStatusCode().value(), new String(limited, StandardCharsets.UTF_8));
                }
                byte[] bytes = response.getBody().readNBytes(maxImageJsonBytes + 1);
                if (bytes.length > maxImageJsonBytes) throw new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE);
                return new String(bytes, StandardCharsets.UTF_8);
            });
        } catch (RestClientResponseException e) {
            throw UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.timeout();
        }
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
        if (operation != ModelOperation.RESPONSES) throw unsupported();
        return webClient.post().uri(url(context))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + context.getApiKey())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody(context)).retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .onErrorMap(WebClientResponseException.class,
                        e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout());
    }

    private String requestBody(ForwardContext context) {
        JSONObject body;
        try { body = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { throw unsupported(); }
        if (body == null) throw unsupported();
        if (operation == ModelOperation.EMBEDDINGS) validateEmbedding(body);
        if (operation == ModelOperation.IMAGE_GENERATION) validateImage(body);
        body.put("model", context.getModelName());
        return JSON.toJSONString(body);
    }

    private void validateEmbedding(JSONObject body) {
        EmbeddingPayload.validate(body);
    }

    private void validateImage(JSONObject body) {
        if (!StringUtils.hasText(body.getString("prompt"))) throw unsupported();
        String format = body.getString("response_format");
        if (format != null && !Set.of("url", "b64_json").contains(format)) throw unsupported();
        Integer count = body.getInteger("n");
        if (count != null && (count < 1 || count > 10)) throw unsupported();
    }

    private String url(ForwardContext context) {
        String base = context.getApiUrl();
        if (!StringUtils.hasText(base)) throw unsupported();
        return base.replaceAll("/+$", "") + path;
    }

    private ClientException unsupported() { return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED); }

    @Configuration(proxyBeanMethods = false)
    public static class Registrations {
        @Bean public OpenAiJsonOperationAdapter responsesAdapter() {
            return new OpenAiJsonOperationAdapter(ModelOperation.RESPONSES, "/responses");
        }
        @Bean public OpenAiJsonOperationAdapter embeddingsAdapter() {
            return new OpenAiJsonOperationAdapter(ModelOperation.EMBEDDINGS, "/embeddings");
        }
        @Bean public OpenAiJsonOperationAdapter imagesAdapter() {
            return new OpenAiJsonOperationAdapter(ModelOperation.IMAGE_GENERATION, "/images/generations");
        }
    }
}
