package com.yonagi.verse.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.AdapterExchange;
import com.yonagi.verse.service.forward.InFlightRequestCoalescer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CapabilityForwardControllerTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private LlmForwardService service;
    private LlmForwardController controller;

    @BeforeEach void setUp() {
        service = mock(LlmForwardService.class);
        controller = new LlmForwardController(service, new InFlightRequestCoalescer());
        UserContextHolder.set(new UserContext().setUserId(1L).setCurrentTenantId(2L).setApiKeyId(3L));
    }

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void responsesNonStreamAndNamedStreamKeepShapeAndTrace() throws Exception {
        when(service.jsonCompletion(any(), eq(ModelOperation.RESPONSES), anyString(), anyString(), any()))
                .thenReturn("{\"object\":\"response\",\"id\":\"r1\"}");
        var response = controller.responses("{\"model\":\"alias\"}");
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
        assertEquals("response", JSON_MAPPER.readTree(Flux.from(response.getBody()).blockFirst())
                .path("object").textValue());

        when(service.responsesStream(any(), anyString(), anyString(), any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder("{\"type\":\"response.created\"}")
                        .event("response.created").id("1").build(),
                ServerSentEvent.<String>builder("{\"type\":\"response.completed\"}")
                        .event("response.completed").id("2").build()));
        var streamed = controller.responses("{\"model\":\"alias\",\"stream\":true}");
        assertEquals(MediaType.TEXT_EVENT_STREAM, streamed.getHeaders().getContentType());
        var events = Flux.from(streamed.getBody()).collectList().block();
        assertNotNull(events);
        assertEquals(2, events.size());
        assertTrue(events.get(0).startsWith("id: 1\nevent: response.created\ndata:"));
        assertTrue(events.get(1).contains("event: response.completed"));
    }

    @Test void upstreamCapacityRejectionReturnsServiceUnavailableWithTrace() throws Exception {
        when(service.jsonCompletion(any(), eq(ModelOperation.EMBEDDINGS), anyString(), anyString(), any()))
                .thenThrow(new ClientException(LlmForwardErrorCodeEnum.UPSTREAM_CAPACITY_EXCEEDED));
        var response = controller.embeddings("{\"model\":\"alias\",\"input\":\"hi\"}");
        assertEquals(503, response.getStatusCode().value());
        JsonNode error = JSON_MAPPER.readTree(Flux.from(response.getBody()).blockFirst()).path("error");
        assertEquals("B000802", error.path("code").textValue());
        assertEquals("server_error", error.path("type").textValue());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
    }

    @Test void embeddingsRequireExactOperationAndReturnOpenAiError() throws Exception {
        when(service.jsonCompletion(any(), eq(ModelOperation.EMBEDDINGS), anyString(), anyString(), any()))
                .thenThrow(new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED));
        var response = controller.embeddings("{\"model\":\"chat-only\",\"input\":\"hi\"}");
        assertEquals(400, response.getStatusCode().value());
        JsonNode error = JSON_MAPPER.readTree(Flux.from(response.getBody()).blockFirst()).path("error");
        assertEquals("A000807", error.path("code").textValue());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
    }

    @Test void chatEmbeddingsAndImagesReturnJsonWithTrace() throws Exception {
        when(service.chatCompletion(any(), anyString(), anyString(), any()))
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        var chat = controller.chatCompletion("{\"model\":\"alias\",\"messages\":[]}");
        assertEquals(200, chat.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, chat.getHeaders().getContentType());
        assertNotNull(chat.getHeaders().getFirst("x-request-id"));
        assertEquals("hi", JSON_MAPPER.readTree(Flux.from(chat.getBody()).blockFirst())
                .at("/choices/0/message/content").textValue());

        when(service.jsonCompletion(any(), eq(ModelOperation.EMBEDDINGS), anyString(), anyString(), any()))
                .thenReturn("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}");
        var embeddings = controller.embeddings("{\"model\":\"alias\",\"input\":\"hi\"}");
        assertEquals(200, embeddings.getStatusCode().value());
        assertNotNull(embeddings.getHeaders().getFirst("x-request-id"));
        assertEquals(0, JSON_MAPPER.readTree(Flux.from(embeddings.getBody()).blockFirst())
                .at("/data/0/index").numberValue());

        when(service.jsonCompletion(any(), eq(ModelOperation.IMAGE_GENERATION), anyString(), anyString(), any()))
                .thenReturn("{\"data\":[{\"url\":\"https://example.test/image.png\"}]}");
        var images = controller.images("{\"model\":\"alias\",\"prompt\":\"cat\"}");
        assertEquals(200, images.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, images.getHeaders().getContentType());
        assertNotNull(images.getHeaders().getFirst("x-request-id"));
        assertEquals(1, JSON_MAPPER.readTree(Flux.from(images.getBody()).blockFirst()).path("data").size());
    }

    @Test void modelsReturnOrderedTenantAliasesAndEmptyListsInOpenAiShape() throws Exception {
        when(service.listModels(2L)).thenReturn(List.of("model-b", "model-a"), List.of());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/openai/models"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"object":"list","data":[{"id":"model-b","object":"model"},
                        {"id":"model-a","object":"model"}]}
                        """, true));
        mvc.perform(get("/api/v1/openai/models"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"object\":\"list\",\"data\":[]}", true));
    }

    @Test void speechReturnsBinaryMediaTypeAndTrace() {
        byte[] audio = "abc".getBytes(StandardCharsets.UTF_8);
        when(service.media(any(), eq(ModelOperation.SPEECH), eq("alias"), any(), anyString(), any()))
                .thenReturn(new AdapterExchange.BinaryResult(audio, MediaType.parseMediaType("audio/mpeg"),
                        AdapterExchange.UsageEvidence.unknown(), 200, Map.of()));
        var response = controller.speech("{\"model\":\"alias\",\"input\":\"hi\",\"voice\":\"alloy\"}");
        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.getHeaders().getContentType());
        assertArrayEquals(audio, (byte[]) response.getBody());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
    }

    @Test void oversizedTranscriptionIsRejectedBeforeServiceInvocation() {
        byte[] audio = new byte[25 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "audio.wav", "audio/wav", audio);
        var response = controller.transcribe("alias", file, null, null, null, null);
        assertEquals(413, response.getStatusCode().value());
        verify(service, never()).media(any(), any(), anyString(), any(), anyString(), any());
    }

    @Test void transcriptionReturnsBodyMediaTypeAndTrace() {
        byte[] transcript = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        when(service.media(any(), eq(ModelOperation.TRANSCRIPTION), eq("alias"), any(), anyString(), any()))
                .thenReturn(new AdapterExchange.BinaryResult(transcript, MediaType.APPLICATION_JSON,
                        AdapterExchange.UsageEvidence.unknown(), 200, Map.of()));
        MockMultipartFile file = new MockMultipartFile("file", "audio.wav", "audio/wav",
                "audio".getBytes(StandardCharsets.UTF_8));
        var response = controller.transcribe("alias", file, null, null, null, null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
        assertArrayEquals(transcript, (byte[]) response.getBody());
    }

    @Test void mediaFailuresSerializeOpenAiErrorInsteadOfPublisherMetadata() throws Exception {
        when(service.media(any(), any(), anyString(), any(), anyString(), any()))
                .thenThrow(new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/openai/audio/speech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"missing\",\"input\":\"hi\",\"voice\":\"alloy\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("x-request-id"))
                .andExpect(jsonPath("$.error.code").value("A000800"));
        mvc.perform(multipart("/api/v1/openai/audio/transcriptions")
                        .file(new MockMultipartFile("file", "clip.wav", "audio/wav", new byte[] {1, 2}))
                        .param("model", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("x-request-id"))
                .andExpect(jsonPath("$.error.code").value("A000800"));
    }

    @Test void rerankExtensionReturnsJsonAndTrace() {
        RerankController rerank = new RerankController(service);
        when(service.jsonCompletion(any(), eq(ModelOperation.RERANK), anyString(), anyString(), any()))
                .thenReturn("{\"results\":[{\"index\":1,\"relevance_score\":0.8}]}");
        var response = rerank.rerank("{\"model\":\"alias\",\"query\":\"q\",\"documents\":[\"a\",\"b\"]}");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNotNull(response.getHeaders().getFirst("x-request-id"));
    }

    @ParameterizedTest
    @CsvSource({
            "MODEL_NOT_FOUND,404,invalid_request_error,A000800",
            "API_KEY_INVALID,401,invalid_request_error,A000801",
            "RATE_LIMIT_EXCEEDED,429,invalid_request_error,A000802",
            "REQUEST_TOO_LARGE,413,invalid_request_error,A000806",
            "UPSTREAM_ERROR,502,upstream_error,C000800",
            "FORWARD_FAILED,400,server_error,B000800"
    })
    void rerankRejectionReturnsCompatibleErrorEnvelope(LlmForwardErrorCodeEnum error, int expectedStatus,
                                                       String expectedType, String expectedCode) throws Exception {
        when(service.jsonCompletion(any(), eq(ModelOperation.RERANK), anyString(), anyString(), any()))
                .thenThrow(new ClientException("Rerank rejected", error));
        var mvc = MockMvcBuilders.standaloneSetup(new RerankController(service)).build();
        mvc.perform(post("/api/v1/rerank").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"missing\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("x-request-id"))
                .andExpect(content().json("""
                        {"error":{"message":"Rerank rejected","type":"%s","code":"%s"}}
                        """.formatted(expectedType, expectedCode), true));
    }
}
