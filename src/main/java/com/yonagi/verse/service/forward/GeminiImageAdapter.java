package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Gemini GenerateContent 图片输出映射为 OpenAI b64_json 图片列表。 */
@Component
public class GeminiImageAdapter implements ProviderAdapter, AdapterRegistration {
    private final RestClient client;
    @Value("${verse.llm.media.max-image-json-bytes:8388608}")
    private int maxImageJsonBytes = 8 * 1024 * 1024;

    public GeminiImageAdapter() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public ModelOperation operation() { return ModelOperation.IMAGE_GENERATION; }
    @Override public UpstreamProtocol protocol() { return UpstreamProtocol.GEMINI_GENERATE_CONTENT; }
    @Override public String provider() { return "gemini"; }

    @Override public String forward(ForwardContext context) {
        JSONObject input;
        try { input = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { throw unsupported(); }
        if (input == null || !Set.of("model", "prompt", "response_format", "n").containsAll(input.keySet())
                || input.getString("prompt") == null || input.getString("prompt").isBlank()
                || input.getInteger("n") != null && input.getInteger("n") != 1
                || input.getString("response_format") != null
                && !"b64_json".equals(input.getString("response_format"))) throw unsupported();
        JSONObject part = new JSONObject();
        part.put("text", input.getString("prompt"));
        JSONArray parts = new JSONArray();
        parts.add(part);
        JSONObject content = new JSONObject();
        content.put("parts", parts);
        JSONArray contents = new JSONArray();
        contents.add(content);
        JSONObject settings = new JSONObject();
        settings.put("responseModalities", new String[] {"IMAGE"});
        JSONObject outbound = new JSONObject();
        outbound.put("contents", contents);
        outbound.put("generationConfig", settings);
        String url = context.getApiUrl().replaceAll("/+$", "") + "/models/"
                + context.getModelName() + ":generateContent";
        try {
            String raw = client.post().uri(url).header("x-goog-api-key", context.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(outbound))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            byte[] limited = response.getBody().readNBytes(1024);
                            throw UpstreamErrors.from(response.getStatusCode().value(),
                                    new String(limited, StandardCharsets.UTF_8));
                        }
                        byte[] bytes = response.getBody().readNBytes(maxImageJsonBytes + 1);
                        if (bytes.length > maxImageJsonBytes) throw new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE);
                        return new String(bytes, StandardCharsets.UTF_8);
                    });
            JSONObject upstream = JSON.parseObject(raw);
            JSONArray candidates = upstream == null ? null : upstream.getJSONArray("candidates");
            JSONArray data = new JSONArray();
            if (candidates != null) for (Object item : candidates) {
                if (!(item instanceof JSONObject candidate)) continue;
                JSONObject generated = candidate.getJSONObject("content");
                JSONArray produced = generated == null ? null : generated.getJSONArray("parts");
                if (produced != null) for (Object producedItem : produced) {
                    if (!(producedItem instanceof JSONObject producedPart)) continue;
                    JSONObject inline = producedPart.getJSONObject("inlineData");
                    if (inline == null) continue;
                    JSONObject image = new JSONObject();
                    image.put("b64_json", inline.getString("data"));
                    data.add(image);
                }
            }
            if (data.isEmpty()) throw UpstreamErrors.from(502, null);
            JSONObject result = new JSONObject();
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("data", data);
            return JSON.toJSONString(result);
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.timeout();
        }
    }

    @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) { return Flux.error(unsupported()); }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }
}
