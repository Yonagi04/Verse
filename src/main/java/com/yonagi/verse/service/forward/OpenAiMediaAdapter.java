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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** OpenAI 兼容及 Azure 的有界音频传输。 */
public class OpenAiMediaAdapter implements ProviderAdapter, MediaOperationAdapter, AdapterRegistration {
    @Value("${verse.llm.media.max-upload-bytes:26214400}")
    private int maxUploadBytes = 25 * 1024 * 1024;
    @Value("${verse.llm.media.max-output-bytes:26214400}")
    private int maxOutputBytes = 25 * 1024 * 1024;
    private final ModelOperation operation;
    private final UpstreamProtocol protocol;
    private final RestClient client;

    public OpenAiMediaAdapter(ModelOperation operation, UpstreamProtocol protocol) {
        this.operation = operation;
        this.protocol = protocol;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public ModelOperation operation() { return operation; }
    @Override public UpstreamProtocol protocol() { return protocol; }
    @Override public String provider() { return protocol == UpstreamProtocol.OPENAI_COMPAT ? null : "azure"; }
    @Override public String forward(ForwardContext context) { throw unsupported(); }
    @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) { return Flux.error(unsupported()); }

    @Override public AdapterExchange.Result invoke(ForwardContext context, AdapterExchange.Request request) {
        if (request.operation() != operation) throw unsupported();
        return operation == ModelOperation.TRANSCRIPTION
                ? transcribe(context, request) : speech(context, request);
    }

    private AdapterExchange.Result transcribe(ForwardContext context, AdapterExchange.Request request) {
        if (!(request instanceof AdapterExchange.MultipartRequest multipart)) throw unsupported();
        byte[] file = multipart.file();
        if (file.length == 0 || file.length > maxUploadBytes) {
            throw new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE);
        }
        String mime = multipart.fileType().toString();
        if (!Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/wav", "audio/x-wav",
                "audio/webm", "audio/ogg", "audio/flac", "audio/x-m4a").contains(mime)) throw unsupported();
        String format = multipart.fields().getOrDefault("response_format", "json");
        if (!Set.of("json", "text", "srt", "vtt", "verbose_json").contains(format)) throw unsupported();
        if (!Set.of("response_format", "language", "prompt", "temperature").containsAll(multipart.fields().keySet())) {
            throw unsupported();
        }
        ByteArrayResource resource = new ByteArrayResource(file) {
            @Override public String getFilename() { return multipart.filename() == null ? "audio" : multipart.filename(); }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(multipart.fileType());
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("model", model(context));
        parts.add("file", new HttpEntity<>(resource, fileHeaders));
        multipart.fields().forEach(parts::add);
        RestClient.RequestBodySpec outbound = client.post().uri(url(context)).contentType(MediaType.MULTIPART_FORM_DATA);
        outbound = authenticate(outbound, context);
        byte[] bytes = read(outbound.body(parts));
        MediaType resultType = switch (format) {
            case "json", "verbose_json" -> MediaType.APPLICATION_JSON;
            case "vtt" -> MediaType.parseMediaType("text/vtt");
            case "srt" -> MediaType.parseMediaType("application/x-subrip");
            default -> MediaType.TEXT_PLAIN;
        };
        JSONObject json = null;
        if (MediaType.APPLICATION_JSON.equals(resultType)) {
            try { json = JSON.parseObject(bytes); }
            catch (RuntimeException ignored) { throw UpstreamErrors.from(502, null); }
        }
        Long duration = json == null || json.getDouble("duration") == null ? null
                : Math.round(json.getDouble("duration") * 1000);
        AdapterExchange.UsageEvidence usage = new AdapterExchange.UsageEvidence(null, null, null,
                null, duration, null, json == null ? null : json.getJSONObject("usage"));
        return new AdapterExchange.BinaryResult(bytes, resultType, usage, 200,
                Map.of("filename", multipart.filename() == null ? "audio" : multipart.filename(),
                        "mime", mime, "bytes", String.valueOf(file.length)));
    }

    private AdapterExchange.Result speech(ForwardContext context, AdapterExchange.Request request) {
        if (!(request instanceof AdapterExchange.JsonRequest jsonRequest)) throw unsupported();
        JSONObject body = JSON.parseObject(JSON.toJSONString(jsonRequest.body()));
        if (!Set.of("model", "input", "voice", "response_format", "speed", "instructions").containsAll(body.keySet())
                || body.getString("input") == null || body.getString("input").isBlank()
                || body.getString("voice") == null || body.getString("voice").isBlank()) throw unsupported();
        String format = body.getString("response_format");
        if (format == null) format = "mp3";
        MediaType type = switch (format) {
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            case "opus" -> MediaType.parseMediaType("audio/opus");
            case "aac" -> MediaType.parseMediaType("audio/aac");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "pcm" -> MediaType.parseMediaType("audio/pcm");
            default -> throw unsupported();
        };
        body.put("model", model(context));
        byte[] bytes = read(authenticate(client.post().uri(url(context)).contentType(MediaType.APPLICATION_JSON), context)
                .body(JSON.toJSONString(body)));
        return new AdapterExchange.BinaryResult(bytes, type, AdapterExchange.UsageEvidence.unknown(),
                200, Map.of("format", format, "bytes", String.valueOf(bytes.length)));
    }

    private RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context) {
        return request.header(protocol == UpstreamProtocol.OPENAI_COMPAT
                        ? HttpHeaders.AUTHORIZATION : "api-key",
                protocol == UpstreamProtocol.OPENAI_COMPAT ? "Bearer " + context.getApiKey() : context.getApiKey());
    }

    private String model(ForwardContext context) {
        if (protocol != UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT) return context.getModelName();
        JSONObject settings = JSON.parseObject(context.getProviderSettings());
        return settings.getString("deployment");
    }

    private String url(ForwardContext context) {
        String base = context.getApiUrl().replaceAll("/+$", "");
        String path = operation == ModelOperation.SPEECH ? "/audio/speech" : "/audio/transcriptions";
        if (protocol == UpstreamProtocol.OPENAI_COMPAT) return base + path;
        if (protocol == UpstreamProtocol.AZURE_OPENAI_V1) {
            return (base.endsWith("/openai/v1") ? base : base + "/openai/v1") + path;
        }
        JSONObject settings = JSON.parseObject(context.getProviderSettings());
        if (settings == null || settings.getString("deployment") == null
                || settings.getString("apiVersion") == null) throw unsupported();
        return base + "/openai/deployments/" + settings.getString("deployment") + path
                + "?api-version=" + settings.getString("apiVersion");
    }

    private byte[] read(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((outbound, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (!status.is2xxSuccessful()) {
                    byte[] limited = response.getBody().readNBytes(1024);
                    throw UpstreamErrors.from(status.value(), new String(limited, java.nio.charset.StandardCharsets.UTF_8));
                }
                byte[] bytes = response.getBody().readNBytes(maxOutputBytes + 1);
                if (bytes.length > maxOutputBytes) throw new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE);
                return bytes;
            });
        } catch (ResourceAccessException e) {
            throw UpstreamErrors.timeout();
        }
    }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }

    @Configuration(proxyBeanMethods = false)
    public static class Registrations {
        @Bean public OpenAiMediaAdapter compatibleSpeechAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.OPENAI_COMPAT);
        }
        @Bean public OpenAiMediaAdapter compatibleTranscriptionAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION, UpstreamProtocol.OPENAI_COMPAT);
        }
        @Bean public OpenAiMediaAdapter azureV1SpeechAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.AZURE_OPENAI_V1);
        }
        @Bean public OpenAiMediaAdapter azureDeploymentSpeechAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT);
        }
        @Bean public OpenAiMediaAdapter azureV1TranscriptionAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION, UpstreamProtocol.AZURE_OPENAI_V1);
        }
        @Bean public OpenAiMediaAdapter azureDeploymentTranscriptionAdapter() {
            return new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT);
        }
    }
}
