package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Azure OpenAI v1 与部署路由的显式适配器。 */
public class AzureJsonOperationAdapter implements ProviderAdapter, AdapterRegistration {
    private final ModelOperation operation;
    private final UpstreamProtocol protocol;
    private final RestClient client;
    private final WebClient webClient = WebClient.create();
    @Value("${verse.llm.media.max-image-json-bytes:8388608}")
    private int maxImageJsonBytes = 8 * 1024 * 1024;

    public AzureJsonOperationAdapter(ModelOperation operation, UpstreamProtocol protocol) {
        this.operation = operation;
        this.protocol = protocol;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public ModelOperation operation() { return operation; }
    @Override public UpstreamProtocol protocol() { return protocol; }
    @Override public String provider() { return "azure"; }

    @Override public String forward(ForwardContext context) {
        try {
            var request = client.post().uri(url(context)).header("api-key", context.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body(context));
            if (operation == ModelOperation.EMBEDDINGS) {
                JSONObject input = JSON.parseObject(context.getBody());
                return EmbeddingPayload.normalize(request.retrieve().body(String.class),
                        EmbeddingPayload.validate(input), input.getInteger("dimensions"));
            }
            if (operation != ModelOperation.IMAGE_GENERATION) return request.retrieve().body(String.class);
            return request.exchange((outbound, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    byte[] limited = response.getBody().readNBytes(1024);
                    throw UpstreamErrors.from(response.getStatusCode().value(),
                            new String(limited, StandardCharsets.UTF_8));
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

    @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
        if (operation != ModelOperation.RESPONSES) return Flux.error(unsupported());
        return webClient.post().uri(url(context)).header("api-key", context.getApiKey())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body(context)).retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .onErrorMap(WebClientResponseException.class,
                        e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout());
    }

    private String body(ForwardContext context) {
        JSONObject input;
        try { input = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { throw unsupported(); }
        if (input == null) throw unsupported();
        if (operation == ModelOperation.EMBEDDINGS) {
            EmbeddingPayload.validate(input);
        }
        if (operation == ModelOperation.IMAGE_GENERATION) {
            if (input.getString("prompt") == null || input.getString("prompt").isBlank()) throw unsupported();
            String format = input.getString("response_format");
            if (format != null && !Set.of("url", "b64_json").contains(format)) throw unsupported();
        }
        JSONObject settings = settings(context);
        input.put("model", protocol == UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT
                ? settings.getString("deployment") : context.getModelName());
        return JSON.toJSONString(input);
    }

    private String url(ForwardContext context) {
        String base = context.getApiUrl();
        if (base == null || base.isBlank()) throw unsupported();
        base = base.replaceAll("/+$", "");
        String path = switch (operation) {
            case RESPONSES -> "/responses";
            case EMBEDDINGS -> "/embeddings";
            case IMAGE_GENERATION -> "/images/generations";
            default -> throw unsupported();
        };
        if (protocol == UpstreamProtocol.AZURE_OPENAI_V1) {
            return (base.endsWith("/openai/v1") ? base : base + "/openai/v1") + path;
        }
        JSONObject settings = settings(context);
        String deployment = settings.getString("deployment");
        String version = settings.getString("apiVersion");
        if (deployment == null || version == null) throw unsupported();
        return base + "/openai/deployments/" + deployment + path + "?api-version=" + version;
    }

    private JSONObject settings(ForwardContext context) {
        try {
            JSONObject settings = JSON.parseObject(context.getProviderSettings());
            return settings == null ? new JSONObject() : settings;
        } catch (RuntimeException e) { throw unsupported(); }
    }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }

    @Configuration(proxyBeanMethods = false)
    public static class Registrations {
        @Bean public AzureJsonOperationAdapter azureResponsesAdapter() {
            return new AzureJsonOperationAdapter(ModelOperation.RESPONSES, UpstreamProtocol.AZURE_OPENAI_V1);
        }
        @Bean public AzureJsonOperationAdapter azureV1EmbeddingsAdapter() {
            return new AzureJsonOperationAdapter(ModelOperation.EMBEDDINGS, UpstreamProtocol.AZURE_OPENAI_V1);
        }
        @Bean public AzureJsonOperationAdapter azureDeploymentEmbeddingsAdapter() {
            return new AzureJsonOperationAdapter(ModelOperation.EMBEDDINGS, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT);
        }
        @Bean public AzureJsonOperationAdapter azureV1ImagesAdapter() {
            return new AzureJsonOperationAdapter(ModelOperation.IMAGE_GENERATION, UpstreamProtocol.AZURE_OPENAI_V1);
        }
        @Bean public AzureJsonOperationAdapter azureDeploymentImagesAdapter() {
            return new AzureJsonOperationAdapter(ModelOperation.IMAGE_GENERATION, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT);
        }
    }
}
