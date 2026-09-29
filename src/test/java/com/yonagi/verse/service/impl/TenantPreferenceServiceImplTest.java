package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.req.TenantPreferenceReqDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantPreferenceServiceImplTest {
    @Test
    void writesOnlyCurrentUsersActiveMembership() {
        UserTenantMapper mapper = mock(UserTenantMapper.class);
        UserTenantDO membership = new UserTenantDO();
        membership.setFavorite(false);
        membership.setPinned(false);
        when(mapper.selectActiveMembership(10L, 20L)).thenReturn(membership);
        when(mapper.updatePreference(10L, 20L, true, true)).thenReturn(1);

        var result = new TenantPreferenceServiceImpl(mapper).update(10L, 20L, request(true, true));

        assertTrue(result.isFavorite());
        assertTrue(result.isPinned());
        verify(mapper).updatePreference(10L, 20L, true, true);
    }

    @Test
    void revokedMembershipCannotWriteOrReadPreference() {
        UserTenantMapper mapper = mock(UserTenantMapper.class);
        assertThrows(ClientException.class,
                () -> new TenantPreferenceServiceImpl(mapper).update(10L, 20L, request(true, false)));
        verify(mapper, never()).updatePreference(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void pinWithoutFavoriteKeepsFavoriteOff() {
        UserTenantMapper mapper = mock(UserTenantMapper.class);
        UserTenantDO membership = new UserTenantDO();
        membership.setFavorite(false);
        membership.setPinned(false);
        when(mapper.selectActiveMembership(10L, 20L)).thenReturn(membership);
        when(mapper.updatePreference(10L, 20L, false, true)).thenReturn(1);

        var result = new TenantPreferenceServiceImpl(mapper).update(10L, 20L, request(false, true));

        assertFalse(result.isFavorite());
        assertTrue(result.isPinned());
        verify(mapper).updatePreference(10L, 20L, false, true);
    }

    @Test
    void removingFavoriteKeepsPinOn() {
        UserTenantMapper mapper = mock(UserTenantMapper.class);
        UserTenantDO membership = new UserTenantDO();
        membership.setFavorite(true);
        membership.setPinned(true);
        when(mapper.selectActiveMembership(10L, 20L)).thenReturn(membership);
        when(mapper.updatePreference(10L, 20L, false, true)).thenReturn(1);

        var result = new TenantPreferenceServiceImpl(mapper).update(10L, 20L, request(false, true));

        assertFalse(result.isFavorite());
        assertTrue(result.isPinned());
    }

    private static TenantPreferenceReqDTO request(boolean favorite, boolean pinned) {
        TenantPreferenceReqDTO request = new TenantPreferenceReqDTO();
        request.setFavorite(favorite);
        request.setPinned(pinned);
        return request;
    }
}
