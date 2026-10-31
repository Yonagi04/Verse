package com.yonagi.verse.dao.mapper.skillhub;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** 清理候选均有界；最终删除许可仍由事务内引用复核决定。 */
public interface SkillCleanupMapper {
    /**
     * @param grace 未引用制品宽限时间
     * @return 最多100个制品候选
     */
    @Select("""
            SELECT a.artifact_id FROM t_skill_artifact a
            WHERE a.status='DELETE_PENDING' OR (a.status='READY'
              AND NOT EXISTS (SELECT v.version_id FROM t_skill_version v WHERE v.artifact_id=a.artifact_id)
              AND NOT EXISTS (SELECT d.draft_id FROM t_skill_draft d
                              WHERE d.artifact_id=a.artifact_id AND d.status IN ('EDITING','READY'))
              AND (a.update_time<=#{grace} OR EXISTS (SELECT c.user_id FROM t_skill_account_cleanup c
                   WHERE c.user_id=a.owner_user_id AND c.state='RUNNABLE')))
            ORDER BY a.artifact_id LIMIT 100
            """)
    List<Long> selectArtifactCandidates(@Param("grace") LocalDateTime grace);

    /**
     * @param artifactId 制品
     * @return 是否仍被不可变版本或活跃草稿引用
     */
    @Select("""
            SELECT (EXISTS (SELECT v.version_id FROM t_skill_version v WHERE v.artifact_id=#{artifactId})
              OR EXISTS (SELECT d.draft_id FROM t_skill_draft d
                         WHERE d.artifact_id=#{artifactId} AND d.status IN ('EDITING','READY')))
            """)
    boolean selectArtifactReferenced(@Param("artifactId") Long artifactId);

    /**
     * @param sourceExpiry 源包保留截止
     * @return 最多100个超期或已注销账号源包任务
     */
    @Select("""
            SELECT j.job_id FROM t_skill_job j WHERE j.source_key IS NOT NULL
              AND j.status IN ('SUCCEEDED','FAILED','CANCELLED','WAITING_UPLOAD')
              AND (j.update_time<=#{sourceExpiry} OR EXISTS (SELECT c.user_id FROM t_skill_account_cleanup c
                   WHERE c.user_id=j.owner_user_id AND c.state='RUNNABLE'))
            ORDER BY j.job_id LIMIT 100
            """)
    List<Long> selectSourceCandidates(@Param("sourceExpiry") LocalDateTime sourceExpiry);

    /**
     * @param key 待删除本域对象Key
     * @return 是否仍由任何持久化记录或有效执行代次持有
     */
    @Select("""
            SELECT (EXISTS (SELECT a.artifact_id FROM t_skill_artifact a WHERE a.archive_key=#{key})
              OR EXISTS (SELECT j.job_id FROM t_skill_job j WHERE j.source_key=#{key})
              OR EXISTS (SELECT j.job_id FROM t_skill_job j WHERE j.status='RUNNING'
                 AND CONCAT('skillhub/v1/artifact/',j.job_id,'/',j.execution_token,'.zip')=#{key}))
            """)
    boolean selectObjectReferenced(@Param("key") String key);

    /**
     * @return 最多100个本域尚可执行的注销处置账号
     */
    @Select("SELECT user_id FROM t_skill_account_cleanup WHERE state='RUNNABLE' ORDER BY user_id LIMIT 100")
    List<Long> selectRunnableAccounts();

    /**
     * @param cutoff 已完成整轮对象扫描且超过在途任务与孤儿宽限的最晚冻结时间
     * @return 最多100个可以复核完成义务的账号
     */
    @Select("SELECT user_id FROM t_skill_account_cleanup WHERE state='RUNNABLE' "
            + "AND create_time<=#{cutoff} ORDER BY user_id LIMIT 100")
    List<Long> selectSettleableAccounts(@Param("cutoff") LocalDateTime cutoff);

    /**
     * @param userId 待处置账号
     * @return 是否还存在私有源包或未处置且未发布制品
     */
    @Select("""
            SELECT (EXISTS (SELECT d.draft_id FROM t_skill_draft d WHERE d.owner_user_id=#{userId}
                AND d.status IN ('CANCELLED','EXPIRED')
                AND (d.metadata_json IS NOT NULL OR d.artifact_id IS NOT NULL OR d.version_label IS NOT NULL))
              OR EXISTS (SELECT j.job_id FROM t_skill_job j WHERE j.owner_user_id=#{userId}
                            AND j.source_key IS NOT NULL)
              OR EXISTS (SELECT a.artifact_id FROM t_skill_artifact a WHERE a.owner_user_id=#{userId}
                 AND a.status<>'DELETED' AND NOT EXISTS (SELECT v.version_id FROM t_skill_version v
                                                        WHERE v.artifact_id=a.artifact_id)))
            """)
    boolean selectPendingAccountObjects(@Param("userId") Long userId);

    /**
     * @param userId 待处置账号
     * @return 是否有已发布制品等待长期政策，不自动删除
     */
    @Select("""
            SELECT EXISTS (SELECT v.version_id FROM t_skill_version v JOIN t_skill s ON s.skill_id=v.skill_id
              JOIN t_skill_publisher p ON p.publisher_id=s.publisher_id WHERE p.user_id=#{userId})
            """)
    boolean selectRetainedPublications(@Param("userId") Long userId);

    /**
     * @param expiry 草稿过期时间
     * @return 最多100个过期草稿
     */
    @Select("SELECT draft_id FROM t_skill_draft WHERE status IN ('EDITING','READY') "
            + "AND update_time<=#{expiry} ORDER BY draft_id LIMIT 100")
    List<Long> selectExpiredDraftIds(@Param("expiry") LocalDateTime expiry);
}
