package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillPublisherStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 既有账号的内部发布关联，不提供另一套作者身份设置。 */
@Service
@RequiredArgsConstructor
public class SkillPublisherService {
    private static final String ACCOUNT_HANDLE_PREFIX = "u_";
    private final SkillPublisherMapper publisherMapper;
    private final AccountAvailabilityService accountAvailabilityService;

    /**
     * @param userId 可信账号；在创建草稿的原事务中首先锁定，避免共享锁升级和重复关联
     * @return 当前账号的稳定关联；历史标识保留，关闭状态不能恢复
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillPublisherDO ensure(Long userId) {
        if (!accountAvailabilityService.lockActiveAccountExclusively(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        SkillPublisherDO publisher = publisherMapper.selectByUserIdForUpdate(userId);
        if (publisher != null) {
            if (!SkillPublisherStatusEnum.ACTIVE.name().equals(publisher.getStatus())) {
                throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
            }
            return publisher;
        }
        String displayName = accountAvailabilityService.readActiveDisplayName(userId);
        if (displayName == null || displayName.isBlank()) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        publisher = new SkillPublisherDO();
        publisher.setPublisherId(SnowflakeIdUtil.nextId());
        publisher.setUserId(userId);
        // 下划线与历史人工标识字符集不相交，内部定位不占用用户名，也不显示为作者名称。
        publisher.setHandle(ACCOUNT_HANDLE_PREFIX + userId);
        publisher.setPublicName(displayName);
        publisher.setStatus(SkillPublisherStatusEnum.ACTIVE.name());
        publisher.setRevision(0L);
        publisher.setCreateTime(now);
        publisher.setUpdateTime(now);
        publisherMapper.insert(publisher);
        return publisher;
    }
}
