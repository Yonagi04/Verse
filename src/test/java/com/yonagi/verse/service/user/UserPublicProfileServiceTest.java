package com.yonagi.verse.service.user;

import com.yonagi.verse.dao.mapper.AccountAvailabilityMapper;
import com.yonagi.verse.dao.projection.UserPublicProfileProjection;
import com.yonagi.verse.service.AvatarService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPublicProfileServiceTest {
    private final AccountAvailabilityMapper mapper = mock(AccountAvailabilityMapper.class);
    private final AvatarService avatars = mock(AvatarService.class);
    private final UserPublicProfileService service = new UserPublicProfileService(mapper, avatars);

    @Test void batchesPublicProfilesAndKeepsAnonymousFallbackForMissingUsers() {
        var row = new UserPublicProfileProjection(); row.setUserId(2L); row.setDisplayName("当前昵称"); row.setAvatar("avatars/2/test.webp");
        when(mapper.selectPublicProfiles(List.of(2L, 3L))).thenReturn(List.of(row));
        when(avatars.publicUrl(row.getAvatar())).thenReturn("https://assets.test/avatar.webp");
        var result = service.read(List.of(2L, 3L));
        assertEquals("当前昵称", result.get(2L).displayName());
        assertEquals("https://assets.test/avatar.webp", result.get(2L).avatarUrl());
        assertEquals("已注销用户", result.get(3L).displayName()); assertNull(result.get(3L).avatarUrl());
    }
    @Test void emptyAndInvalidBatchesDoNotReadPrivateDataOrWholeTable() {
        assertTrue(service.read(List.of()).isEmpty()); verifyNoInteractions(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.read(null));
        assertThrows(IllegalArgumentException.class, () -> service.read(Collections.nCopies(101, 2L)));
        assertThrows(IllegalArgumentException.class, () -> service.read(Arrays.asList(2L, null)));
        assertThrows(IllegalArgumentException.class, () -> service.read(List.of(0L)));
    }
}
