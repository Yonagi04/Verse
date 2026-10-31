package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.SkillImportTaskStatusEnum;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillPublisherStatusEnum;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.UserClosedEvent;
import com.yonagi.verse.dao.entity.skillhub.SkillAccountCleanupDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAccountCleanupMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 注销同步冻结与异步处置进度；保留包待政策时不能宣称全部资源清理完成。 */
@Service
@RequiredArgsConstructor
public class SkillAccountCleanupService {
    private static final String RUNNABLE = "RUNNABLE";
    private static final String COMPLETED = "COMPLETED";
    private final SkillPublisherMapper publisherMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillImportTaskMapper taskMapper;
    private final SkillAccountCleanupMapper cleanupMapper;
    private final ReliableDomainEventPublisher events;

    /**
     * @param userId 注销事务已持用户排他锁的账号
     * 允许历史已注销账号补偿；不从本域反向索取租户或身份锁。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void freeze(Long userId) {
        SkillPublisherDO publisher = publisherMapper.selectByUserIdForUpdate(userId);
        if (publisher == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!SkillPublisherStatusEnum.CLOSED.name().equals(publisher.getStatus())) {
            publisher.setStatus(SkillPublisherStatusEnum.CLOSED.name());
            publisher.setRevision(publisher.getRevision() + 1);
            publisher.setUpdateTime(now);
            publisherMapper.updateById(publisher);
        }
        draftMapper.update(null, Wrappers.lambdaUpdate(SkillDraftDO.class)
                .eq(SkillDraftDO::getOwnerUserId, userId)
                .in(SkillDraftDO::getStatus, SkillDraftStatusEnum.EDITING.name(), SkillDraftStatusEnum.READY.name())
                .set(SkillDraftDO::getStatus, SkillDraftStatusEnum.CANCELLED.name()).setSql("revision=revision+1")
                .set(SkillDraftDO::getUpdateTime, now));
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getOwnerUserId, userId).notIn(SkillImportTaskDO::getStatus,
                        SkillImportTaskStatusEnum.SUCCEEDED.name(), SkillImportTaskStatusEnum.FAILED.name(),
                        SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getUpdateTime, now));
        if (cleanupMapper.selectById(userId) == null) {
            SkillAccountCleanupDO progress = new SkillAccountCleanupDO();
            progress.setUserId(userId);
            progress.setState(RUNNABLE);
            progress.setCreateTime(now);
            progress.setUpdateTime(now);
            cleanupMapper.insert(progress);
        }
    }

    /**
     * @param userId 已持用户锁的注销账号
     * @return 无本域义务或物理处置已完成；保留政策等待返回false
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean isComplete(Long userId) {
        freeze(userId);
        SkillAccountCleanupDO progress = cleanupMapper.selectById(userId);
        return progress == null || COMPLETED.equals(progress.getState());
    }

    /**
     * @param userId 历史注销账号
     * @return 已完成本轮可执行处置、仅等待长期保留政策时true，不再补发清理事件
     */
    public boolean isWaitingForRetentionPolicy(Long userId) {
        SkillAccountCleanupDO progress = cleanupMapper.selectById(userId);
        return progress != null && "WAITING_RETENTION_POLICY".equals(progress.getState());
    }

    /**
     * @param userId 独立清理器已确认没有临时对象义务的账号
     * @param retainedPublishedPackage 是否仍保留已发布私有包
     * 等待政策不发无限续跑，只有全部完成才唤醒原注销用例。
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(Long userId, boolean retainedPublishedPackage) {
        String state = retainedPublishedPackage ? "WAITING_RETENTION_POLICY" : COMPLETED;
        int changed = cleanupMapper.update(null, Wrappers.lambdaUpdate(SkillAccountCleanupDO.class)
                .eq(SkillAccountCleanupDO::getUserId, userId).eq(SkillAccountCleanupDO::getState, RUNNABLE)
                .set(SkillAccountCleanupDO::getState, state)
                .set(SkillAccountCleanupDO::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed == 1 && !retainedPublishedPackage) {
            events.publish(UserClosedEvent.next(userId), 0L);
        }
    }
}
