package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Verse Rerank 扩展仅绑定 OpenRouter 原生 Rerank API。 */
@Component
public class OpenRouterRerankAdapter implements ProviderAdapter, AdapterRegistration {
    private final RestClient client;

    public OpenRouterRerankAdapter() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public ModelOperation operation() { return ModelOperation.RERANK; }
    @Override public UpstreamProtocol protocol() { return UpstreamProtocol.OPENROUTER_RERANK; }
    @Override public String provider() { return "openrouter"; }

    @Override
    public String forward(ForwardContext context) {
        JSONObject input;
        try { input = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { throw unsupported(); }
        if (input == null || input.getString("query") == null || input.getString("query").isBlank()) throw unsupported();
        if (!Set.of("model", "query", "documents", "top_n").containsAll(input.keySet())) throw unsupported();
        JSONArray documents = input.getJSONArray("documents");
        if (documents == null || documents.isEmpty()) throw unsupported();
        for (Object document : documents) {
            if (!(document instanceof String value) || value.isBlank()) throw unsupported();
        }
        Integer topN = input.getInteger("top_n");
        if (topN != null && (topN < 1 || topN > documents.size())) throw unsupported();
        input.put("model", context.getModelName());
        try {
            String raw = client.post().uri(context.getApiUrl().replaceAll("/+$", "") + "/rerank")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + context.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(input))
                    .retrieve().body(String.class);
            JSONObject result = JSON.parseObject(raw);
            JSONArray ranked = result == null ? null : result.getJSONArray("results");
            if (ranked == null) throw UpstreamErrors.from(502, null);
            List<JSONObject> rows = new ArrayList<>();
            for (Object item : ranked) {
                if (!(item instanceof JSONObject row)) throw UpstreamErrors.from(502, null);
                Integer index = row.getInteger("index");
                Number score = row.getObject("relevance_score", Number.class);
                if (index == null || index < 0 || index >= documents.size() || score == null) {
                    throw UpstreamErrors.from(502, null);
                }
                rows.add(row);
            }
            rows.sort(Comparator.comparingDouble((JSONObject row) ->
                    row.getDoubleValue("relevance_score")).reversed());
            JSONArray sorted = new JSONArray();
            sorted.addAll(rows);
            result.put("results", sorted);
            return JSON.toJSONString(result);
        } catch (RestClientResponseException e) {
            throw UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.timeout();
        }
    }

    @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
        return Flux.error(unsupported());
    }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }
}
