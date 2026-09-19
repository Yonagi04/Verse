package com.yonagi.verse.async.activity;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.*;
import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantActivityRecorderTest {
    @BeforeAll static void initTableInfo(){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"tenant-activity"),TenantDO.class);
    }
    @Test void disabledTenantSkipsOrdinaryEventButToggleIsAlwaysRecorded() {
        TenantMapper tenantMapper=mock(TenantMapper.class); ActorSnapshotResolver resolver=mock(ActorSnapshotResolver.class);
        ReliableDomainEventPublisher publisher=mock(ReliableDomainEventPublisher.class); DomainOutboxProperties properties=new DomainOutboxProperties();
        TenantDO tenant=new TenantDO(); tenant.setTenantId(20L); tenant.setActivityRecordingEnabled(0);
        when(tenantMapper.selectOne(any())).thenReturn(tenant); when(resolver.resolve(10L)).thenReturn(new ActorSnapshot(10L,"alice","小艾"));
        TenantActivityRecorder recorder=new TenantActivityRecorder(tenantMapper,resolver,publisher,properties);
        TenantActivityDraft draft=TenantActivityDraft.of(TenantActivityType.TENANT_PROFILE_UPDATED).actor(10L)
                .target(TenantActivityTargetType.TENANT,20L,"Verse").detail("changedFields",java.util.List.of("name"));
        assertFalse(recorder.record(20L,draft)); verifyNoInteractions(publisher);
        TenantActivityDraft toggle=TenantActivityDraft.of(TenantActivityType.ACTIVITY_RECORDING_ENABLED).actor(10L)
                .target(TenantActivityTargetType.TENANT,20L,"Verse").detail("changedFields",java.util.List.of("activityRecordingEnabled"));
        recorder.recordToggle(20L,toggle);
        ArgumentCaptor<com.yonagi.verse.async.api.DomainEvent> captor=ArgumentCaptor.forClass(com.yonagi.verse.async.api.DomainEvent.class);
        verify(publisher).publish(captor.capture(),eq(20L)); TenantActivityEvent event=(TenantActivityEvent)captor.getValue();
        assertEquals("20",event.getKey()); assertEquals("alice",event.getActorUsername()); assertEquals("小艾",event.getActorNickname());
    }

    @Test void rejectsNonWhitelistedAndOversizedDetails() {
        assertThrows(IllegalArgumentException.class,()->TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_CREATED).detail("apiKey","secret"));
        TenantMapper mapper=mock(TenantMapper.class); TenantDO tenant=new TenantDO(); tenant.setActivityRecordingEnabled(1); when(mapper.selectOne(any())).thenReturn(tenant);
        ActorSnapshotResolver resolver=mock(ActorSnapshotResolver.class); when(resolver.resolve(1L)).thenReturn(new ActorSnapshot(1L,"u","n"));
        DomainOutboxProperties properties=new DomainOutboxProperties(); properties.setDetailsMaxBytes(256);
        TenantActivityRecorder recorder=new TenantActivityRecorder(mapper,resolver,mock(ReliableDomainEventPublisher.class),properties);
        TenantActivityDraft draft=TenantActivityDraft.of(TenantActivityType.TENANT_SETTINGS_UPDATED).actor(1L)
                .detail("changedFields",java.util.List.of("x".repeat(300)));
        assertThrows(IllegalArgumentException.class,()->recorder.record(2L,draft));
    }

    @Test void detailCatalogueExcludesSecretsTokensHashesAndRequestHeaders() {
        String allowed = java.util.Arrays.stream(TenantActivityType.values())
                .flatMap(type -> type.allowedDetailKeys().stream())
                .map(String::toLowerCase)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        assertFalse(allowed.contains("apikey"));
        assertFalse(allowed.contains("secret"));
        assertFalse(allowed.contains("token"));
        assertFalse(allowed.contains("hash"));
        assertFalse(allowed.contains("header"));
        assertFalse(allowed.contains("invitecode"));
    }

    @Test void activityCatalogueExcludesApiKeyAndInviteCreation() {
        assertFalse(java.util.Arrays.stream(TenantActivityCategory.values())
                .anyMatch(category -> "API_KEY".equals(category.name())));
        assertFalse(java.util.Arrays.stream(TenantActivityTargetType.values())
                .anyMatch(targetType -> "API_KEY".equals(targetType.name())));
        String activityTypes = java.util.Arrays.stream(TenantActivityType.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
        assertFalse(activityTypes.contains("API_KEY"));
        assertFalse(activityTypes.contains("INVITE_CREATED"));
    }
}
