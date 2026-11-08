package com.yonagi.verse.controller;

import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.playground.WorkbenchTypes.CapabilitiesVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ModelVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.PricingVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RangeVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RoundVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.WorkspaceVO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.ForkReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.UpdateReqDTO;
import com.yonagi.verse.service.PlaygroundWorkbenchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaygroundWorkbenchControllerTest {
    private static final String ROOT = "/api/v1/tenants/2/playground/workbench";
    private static final String RESTORE = ROOT + "/presets/11/restore";
    private final PlaygroundWorkbenchService service = mock(PlaygroundWorkbenchService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlaygroundWorkbenchController(service)).build();

    @BeforeEach
    void setContext() {
        UserContextHolder.set(new UserContext().setUserId(7L).setCurrentTenantId(2L));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00000000000000000001", "2147483647.00000000000001", "2147483648",
            "0", "-1", "1.5", "\"1\"", "true", "null"})
    void restoreRejectsInvalidVersionBeforeHttpBindingCanRoundIt(String value) throws Exception {
        mvc.perform(post(RESTORE).contentType("application/json").content("{\"version\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @Test
    void restoreStillRequiresVersion() throws Exception {
        mvc.perform(post(RESTORE).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"1,1,application/json", "1.0,1,application/json", "1e0,1,application/vnd.verse+json",
            "2147483647,2147483647,application/json"})
    void restorePreservesMathematicalIntegers(String value, int expected, String contentType) throws Exception {
        when(service.restore(any(), eq(2L), eq(11L), eq(expected)))
                .thenReturn(WorkspaceVO.builder().id("11").revision(expected).build());
        mvc.perform(post(RESTORE).contentType(contentType).content("{\"version\":" + value + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(expected));
        verify(service).restore(UserContextHolder.get(), 2L, 11L, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "fork"})
    void nestedConfigReachesUseCaseWithoutDecimalRounding(String operation) throws Exception {
        WorkspaceVO response = WorkspaceVO.builder().id("11").kind("GROUP").build();
        String configJson = "{\"lanes\":[{\"laneId\":\"00000000-0000-0000-0000-000000000001\",\"serviceId\":\"9\","
                + "\"config\":{\"maxTokens\":1.00000000000000000001}}]}";
        String payload = "{\"title\":\"中文\",\"config\":" + configJson
                + ("update".equals(operation) ? ",\"revision\":1.0,\"description\":null}" : "}");
        String root = ROOT + "/groups";
        switch (operation) {
            case "create" -> when(service.create(any(), eq(2L), eq("GROUP"), any())).thenReturn(response);
            case "update" -> {
                when(service.detail(any(), eq(2L), eq(11L))).thenReturn(response);
                when(service.update(any(), eq(2L), eq(11L), any())).thenReturn(response);
            }
            case "fork" -> when(service.fork(any(), eq(2L), eq(11L), any())).thenReturn(response);
            default -> throw new IllegalArgumentException(operation);
        }
        mvc.perform(("update".equals(operation) ? put(root + "/11")
                        : post("fork".equals(operation) ? root + "/11/fork" : root))
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("11"));
        ConfigDTO config;
        String title;
        switch (operation) {
            case "create" -> {
                ArgumentCaptor<CreateReqDTO> body = ArgumentCaptor.forClass(CreateReqDTO.class);
                verify(service).create(any(), eq(2L), eq("GROUP"), body.capture());
                config = body.getValue().getConfig();
                title = body.getValue().getTitle();
            }
            case "update" -> {
                ArgumentCaptor<UpdateReqDTO> body = ArgumentCaptor.forClass(UpdateReqDTO.class);
                verify(service).update(any(), eq(2L), eq(11L), body.capture());
                assertEquals(1, body.getValue().getRevision().intValueExact());
                assertEquals(java.util.Set.of("title", "revision", "config", "description"),
                        body.getValue().getSuppliedFields());
                config = body.getValue().getConfig();
                title = body.getValue().getTitle();
            }
            case "fork" -> {
                ArgumentCaptor<ForkReqDTO> body = ArgumentCaptor.forClass(ForkReqDTO.class);
                verify(service).fork(any(), eq(2L), eq(11L), body.capture());
                config = body.getValue().getConfig();
                title = body.getValue().getTitle();
            }
            default -> throw new IllegalArgumentException(operation);
        }
        assertEquals(new BigDecimal("1.00000000000000000001"),
                config.getLanes().getFirst().getConfig().getMaxTokens());
        assertEquals("中文", title);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{version:1}"})
    void malformedOrNonObjectJsonKeepsMvcRejection(String body) throws Exception {
        mvc.perform(post(RESTORE).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"revision\":1.00000000000000000001}", "{\"revision\":\"1\"}",
            "{\"revision\":null}", "{\"title\":42}", "{\"history\":[]}",
            "{\"config\":{\"lanes\":[{\"serviceId\":9}]}}",
            "{\"config\":{\"lanes\":[{\"serviceId\":\"9\",\"config\":{\"maxTokens\":\"100\"}}]}}"})
    void updateRejectsInvalidFieldsBeforeCallingUseCase(String body) throws Exception {
        mvc.perform(put(ROOT + "/groups/11").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @Test
    void modelsKeepIdentifierCapabilityAndPricingWireShape() throws Exception {
        ModelVO model = new ModelVO("9007199254740993", "模型", "custom", null, 10000L,
                new CapabilitiesVO(true, new RangeVO(0D, 1D), null, 1024L),
                new PricingVO("CNY", BillingMode.TOKEN, new BigDecimal("1.25"), null, null, PricePeriodType.BASE));
        when(service.models(any(), eq(2L))).thenReturn(List.of(model));
        mvc.perform(get(ROOT + "/models")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceId").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].capabilities.maxTokens").value(1024))
                .andExpect(jsonPath("$.data[0].capabilities.topP").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].pricing.inputPriceFen").value(1.25));
    }

    @Test
    void roundPreservesIdempotencyHeaderAndReturnsTypedAttempts() throws Exception {
        String key = "00000000-0000-0000-0000-000000000001";
        when(service.round(any(), eq(2L), eq(11L), eq("问题"), eq(key))).thenReturn(new RoundVO(key, List.of()));
        mvc.perform(post(ROOT + "/groups/11/rounds").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"prompt\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roundId").value(key))
                .andExpect(jsonPath("$.data.attempts").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"lanes\":{}}", "{\"lanes\":[]}", "{\"lanes\":[{},{},{},{}]}",
            "{\"lanes\":[{\"serviceId\":\"9\"}],\"synced\":\"false\"}"})
    void createRejectsInvalidLaneAndSyncShapes(String config) throws Exception {
        mvc.perform(post(ROOT + "/groups").contentType("application/json").content("{\"config\":" + config + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"false,2,0", "null,null,1", "false,\"ignored\",2"})
    void createPreservesPresetSourceAndLaneDefaults(String synced, String version, int variant) throws Exception {
        String lane = variant == 1 ? "{\"serviceId\":\"9\",\"config\":null}" : "{\"serviceId\":\"9\"}";
        String payload = "{\"config\":{\"lanes\":[" + lane + "],\"synced\":" + synced
                + ",\"presetId\":\"9007199254740993\",\"presetVersion\":" + version + "}}";
        when(service.create(any(), eq(2L), eq("GROUP"), any())).thenReturn(WorkspaceVO.builder().id("11").build());
        mvc.perform(post(ROOT + "/groups").contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("11"));
        ArgumentCaptor<CreateReqDTO> command = ArgumentCaptor.forClass(CreateReqDTO.class);
        verify(service).create(any(), eq(2L), eq("GROUP"), command.capture());
        ConfigDTO config = command.getValue().getConfig();
        assertEquals("9007199254740993", config.getPresetId());
        assertEquals(variant == 1, config.getSynced());
        assertNull(config.getLanes().getFirst().getConfig().getMaxTokens());
        assertEquals(variant == 0 ? BigDecimal.valueOf(2) : null, config.getPresetVersion());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void forkCanInheritOriginalConfigWithOrWithoutExplicitNull(boolean before, boolean explicitNull) throws Exception {
        String body = "{\"before\":" + before + ",\"laneId\":\"lane\"" + (explicitNull ? ",\"config\":null}" : "}");
        when(service.fork(any(), eq(2L), eq(11L), any())).thenReturn(WorkspaceVO.builder().id("12").build());
        mvc.perform(post(ROOT + "/groups/11/fork").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("12"));
        ArgumentCaptor<ForkReqDTO> command = ArgumentCaptor.forClass(ForkReqDTO.class);
        verify(service).fork(any(), eq(2L), eq(11L), command.capture());
        assertEquals(before, command.getValue().getBefore());
        assertNull(command.getValue().getConfig());
    }

    @Test
    void rejectsStringForkFlagAndNonTextPrompt() throws Exception {
        mvc.perform(post(ROOT + "/groups/11/fork").contentType("application/json")
                        .content("{\"before\":\"true\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/groups/11/rounds").header("Idempotency-Key", "key")
                        .contentType("application/json").content("{\"prompt\":123}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void presetCannotBeReadAsGroup() throws Exception {
        when(service.detail(any(), eq(2L), eq(11L))).thenReturn(WorkspaceVO.builder().id("11").kind("PRESET").build());
        mvc.perform(get(ROOT + "/groups/11")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
    }
}
