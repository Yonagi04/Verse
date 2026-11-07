package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserSecurityGuardMapper;
import com.yonagi.verse.dto.req.UserUpdateReqDTO;
import com.yonagi.verse.service.EmailQuotaService;
import com.yonagi.verse.service.user.costalert.UserCostAlertCancellationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class UserServiceImplTest {

    @Mock
    private AesUtil aesUtil;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserSecurityGuardMapper securityGuards;

    @Mock
    private EmailQuotaService emailQuota;

    @Mock
    private UserCostAlertCancellationService userCostAlertCancellation;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void profileUpdateFailureDoesNotLogContactDetails(CapturedOutput output) {
        long userId = 42L;
        String email = "private-profile-fixture@example.com";
        String phone = "13800000000";
        UserUpdateReqDTO request = new UserUpdateReqDTO();
        request.setEmail(email);
        request.setPhone(phone);
        UserDO user = new UserDO();
        user.setEmail("encrypted-email");
        user.setPhone("encrypted-phone");
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(aesUtil.decrypt(user.getEmail())).thenReturn(email);
        when(aesUtil.decrypt(user.getPhone())).thenReturn(phone);
        when(userMapper.update(isNull(), any())).thenReturn(-1);

        ServerException failure = assertThrows(ServerException.class, () -> userService.updateProfile(userId, request));

        assertThat(failure.getErrorCode()).isEqualTo(UserErrorCodeEnum.USER_UPDATE_ERROR.code());
        assertThat(output.getAll()).contains("userId", Long.toString(userId)).doesNotContain(email, phone);
    }
}
