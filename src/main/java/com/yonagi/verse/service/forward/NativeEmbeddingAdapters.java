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
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;

import java.util.Set;

/** Gemini 与 Ollama 原生向量响应转换为 OpenAI 索引向量列表。 */
public final class NativeEmbeddingAdapters {
    private NativeEmbeddingAdapters() {}

    private abstract static class Base implements ProviderAdapter, AdapterRegistration {
        final RestClient client;
        Base() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(120000);
            client = RestClient.builder().requestFactory(factory).build();
        }
        @Override public ModelOperation operation() { return ModelOperation.EMBEDDINGS; }
        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) { return Flux.error(unsupported()); }
        static ClientException unsupported() { return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED); }
        static JSONObject input(ForwardContext context) {
            JSONObject object;
            try { object = JSON.parseObject(context.getBody()); }
            catch (RuntimeException e) { throw unsupported(); }
            if (object == null || !Set.of("model", "input", "dimensions").containsAll(object.keySet())) throw unsupported();
            Object value = object.get("input");
            if (!(value instanceof String) && !(value instanceof JSONArray array && !array.isEmpty())) throw unsupported();
            if (value instanceof JSONArray array) for (Object item : array) {
                if (!(item instanceof String)) throw unsupported();
            }
            Integer dimensions = object.getInteger("dimensions");
            if (object.containsKey("dimensions") && (dimensions == null || dimensions <= 0)) throw unsupported();
            return object;
        }
        static JSONObject result(ForwardContext context, JSONArray vectors, Long inputTokens, Integer dimensions,
                                 int expectedCount) {
            if (vectors == null || vectors.size() != expectedCount) throw UpstreamErrors.from(502, null);
            JSONArray data = new JSONArray();
            for (int i = 0; i < vectors.size(); i++) {
                JSONArray vector = vectors.getJSONArray(i);
                if (vector == null || (dimensions != null && vector.size() != dimensions)) {
                    throw UpstreamErrors.from(502, null);
                }
                JSONObject item = new JSONObject();
                item.put("object", "embedding");
                item.put("index", i);
                item.put("embedding", vector);
                data.add(item);
            }
            JSONObject result = new JSONObject();
            result.put("object", "list");
            result.put("model", context.getModelName());
            result.put("data", data);
            if (inputTokens != null) {
                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", inputTokens);
                usage.put("completion_tokens", 0);
                usage.put("total_tokens", inputTokens);
                result.put("usage", usage);
            }
            return result;
        }
    }

    public static final class Ollama extends Base {
        @Override public UpstreamProtocol protocol() { return UpstreamProtocol.OLLAMA_NATIVE; }
        @Override public String provider() { return "ollama"; }
        @Override public String forward(ForwardContext context) {
            JSONObject input = input(context);
            JSONObject outbound = new JSONObject();
            outbound.put("model", context.getModelName());
            outbound.put("input", input.get("input"));
            if (input.containsKey("dimensions")) outbound.put("dimensions", input.get("dimensions"));
            try {
                String raw = client.post().uri(context.getApiUrl().replaceAll("/+$", "") + "/api/embed")
                        .contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(outbound))
                        .retrieve().body(String.class);
                JSONObject response = JSON.parseObject(raw);
                int count = input.get("input") instanceof JSONArray array ? array.size() : 1;
                return JSON.toJSONString(result(context, response.getJSONArray("embeddings"),
                        response.getLong("prompt_eval_count"), input.getInteger("dimensions"), count));
            } catch (RestClientResponseException e) {
                throw UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString());
            } catch (ResourceAccessException e) {
                throw UpstreamErrors.timeout();
            }
        }
    }

    public static final class Gemini extends Base {
        @Override public UpstreamProtocol protocol() { return UpstreamProtocol.GEMINI_GENERATE_CONTENT; }
        @Override public String provider() { return "gemini"; }
        @Override public String forward(ForwardContext context) {
            JSONObject input = input(context);
            JSONArray texts = input.get("input") instanceof JSONArray array ? array : new JSONArray();
            if (texts.isEmpty()) texts.add(input.getString("input"));
            JSONArray requests = new JSONArray();
            for (Object text : texts) {
                JSONObject part = new JSONObject();
                part.put("text", text);
                JSONArray parts = new JSONArray();
                parts.add(part);
                JSONObject content = new JSONObject();
                content.put("parts", parts);
                JSONObject request = new JSONObject();
                request.put("model", "models/" + context.getModelName());
                request.put("content", content);
                if (input.containsKey("dimensions")) request.put("outputDimensionality", input.get("dimensions"));
                requests.add(request);
            }
            JSONObject outbound = new JSONObject();
            outbound.put("requests", requests);
            String url = context.getApiUrl().replaceAll("/+$", "") + "/models/"
                    + context.getModelName() + ":batchEmbedContents";
            try {
                String raw = client.post().uri(url).header("x-goog-api-key", context.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(outbound))
                        .retrieve().body(String.class);
                JSONObject response = JSON.parseObject(raw);
                JSONArray embeddings = response.getJSONArray("embeddings");
                JSONArray vectors = new JSONArray();
                if (embeddings != null) for (Object item : embeddings) {
                    if (!(item instanceof JSONObject embedding)) throw UpstreamErrors.from(502, null);
                    vectors.add(embedding.getJSONArray("values"));
                }
                JSONObject metadata = response.getJSONObject("usageMetadata");
                Long tokens = metadata == null ? null : metadata.getLong("promptTokenCount");
                return JSON.toJSONString(result(context, vectors, tokens,
                        input.getInteger("dimensions"), texts.size()));
            } catch (RestClientResponseException e) {
                throw UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString());
            } catch (ResourceAccessException e) {
                throw UpstreamErrors.timeout();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class Registrations {
        @Bean public Ollama ollamaEmbeddingAdapter() { return new Ollama(); }
        @Bean public Gemini geminiEmbeddingAdapter() { return new Gemini(); }
    }
}
