package com.yonagi.verse.service;
import com.yonagi.verse.dao.mapper.UserSecurityGuardMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Arrays;
import java.util.Objects;
@Service
@RequiredArgsConstructor
public class EmailQuotaService {
    private final UserSecurityGuardMapper guards;
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(String... hashes) {
        // 固定顺序锁定新旧邮箱；人数必须在锁后从数据库读取。
        Arrays.stream(hashes).filter(Objects::nonNull).distinct().sorted().forEach(hash -> {
            guards.ensureEmail(hash); guards.lockEmail(hash);
        });
    }
    public void check(String hash) {
        if (guards.emailCount(hash) >= 3) throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
    }
}
