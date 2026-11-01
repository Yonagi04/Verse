package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 每个GitHub任务的私人来源与选择快照，不代表公开权限。 */
@Getter
@Setter
@TableName("t_skill_github_source")
public class SkillGithubSourceDO {
    /** 与导入任务一对一。 */
    @TableId(type = IdType.INPUT)
    private Long jobId;
    /** 有界规范化来源请求。 */
    private String requestJson;
    /** 待用户确认的解析选项。 */
    private String sourceOptionsJson;
    /** 已确认的固定SHA及目录。 */
    private String confirmedSourceJson;
    /** 私有固定tree对象Key。 */
    private String treeKey;
    /** tree对象实际字节摘要。 */
    private String treeSha256;
    /** 仓库字节与规则共同生成的扫描摘要。 */
    private String scanDigest;
    /** 有界候选清单。 */
    private String candidatesJson;
    /** 已选候选，不能由客户端传任意目录代替。 */
    private String selectedCandidateId;
    /** 可恢复的安全文件问题。 */
    private String validationIssuesJson;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变化时间，UTC。 */
    private LocalDateTime updateTime;
}
