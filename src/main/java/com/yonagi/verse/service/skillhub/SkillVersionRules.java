package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;

import java.util.Set;

/** 编号与轮次规则，只有成功发布事务可以消费编号。 */
public final class SkillVersionRules {
    private SkillVersionRules() {}

    /**
     * @return 平台唯一产品版本标签，不读取旧SemVer字段
     */
    public static String label(SkillVersionDO version) {
        if (version.getReleaseNumber() == null || version.getDraftNumber() == null) {
            throw new ServerException("版本编号尚未迁移", SkillErrorCodeEnum.UNAVAILABLE);
        }
        return "v"
                + version.getReleaseNumber()
                + (version.getDraftNumber() == 0 ? "" : "-draft." + version.getDraftNumber());
    }

    /**
     * @return 当前锁定状态下的下一编号；不会修改输入
     */
    public static Numbering next(
            SkillDO skill, SkillVersionDO release, SkillVersionDO head, String type) {
        if (type == null
                || !Set.of("DRAFT", "RELEASE").contains(type)
                || (release == null && !"RELEASE".equals(type))) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        if (skill.getVersionSequence() == null
                || skill.getVersionSequence() == Long.MAX_VALUE
                || skill.getRevision() == Long.MAX_VALUE
                || (release != null
                        && (release.getReleaseNumber() == null
                                || release.getReleaseNumber() == Integer.MAX_VALUE))) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        int round = release == null ? 1 : release.getReleaseNumber() + 1;
        int draft = 0;
        if ("DRAFT".equals(type)) {
            draft = 1;
            if (head != null
                    && "DRAFT".equals(head.getVersionType())
                    && Integer.valueOf(round).equals(head.getReleaseNumber())) {
                if (head.getDraftNumber() == null || head.getDraftNumber() == Integer.MAX_VALUE) {
                    throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
                }
                draft = head.getDraftNumber() + 1;
            }
        }
        return new Numbering(
                round,
                draft,
                skill.getVersionSequence() + 1,
                "v" + round + (draft == 0 ? "" : "-draft." + draft));
    }

    /** 持锁分配的候选编号。 */
    public record Numbering(int release, int draft, long sequence, String label) {}
}
