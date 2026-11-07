package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.playground.WorkbenchTypes.AttemptVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ModelVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RoundVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.WorkspaceVO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.ForkReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.UpdateReqDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.util.List;

/** 二期工作台契约；所有正文操作均限定当前租户和创建者。 */
public interface PlaygroundWorkbenchService {
    /** 返回当前租户可用模型、工作台能力和当前价格。 */
    List<ModelVO> models(UserContext actor, Long tenant);

    /** 查询本人会话或预设；无匹配时返回空列表。 */
    List<WorkspaceVO> list(UserContext actor, Long tenant, String kind, String keyword);

    /** 创建本人工作区；配置只描述模型栏位，不能注入对话历史。 */
    WorkspaceVO create(UserContext actor, Long tenant, String kind, CreateReqDTO body);

    /** 获取本人资源；会话详情包含已持久化尝试。 */
    WorkspaceVO detail(UserContext actor, Long tenant, Long id);

    /** 更新空闲工作区；过期修订号返回冲突，显式空描述可清空。 */
    WorkspaceVO update(UserContext actor, Long tenant, Long id, UpdateReqDTO body);

    /** 删除本人空闲工作区；正在生成的资源拒绝删除。 */
    boolean delete(UserContext actor, Long tenant, Long id);

    /** 幂等接受一轮调用；同一会话和键返回已有尝试。 */
    RoundVO round(UserContext actor, Long tenant, Long id, String prompt, String key);

    /** 认领本人待执行尝试；重复认领被拒绝，断连触发既有停止流程。 */
    Flux<ServerSentEvent<String>> stream(UserContext actor, Long tenant, Long attempt);

    /** 请求停止本人尝试，保留已收到的部分正文。 */
    boolean stop(UserContext actor, Long tenant, Long attempt);

    /** 以原始快照幂等创建新尝试；只允许重试失败或已停止的尝试。 */
    AttemptVO retry(UserContext actor, Long tenant, Long attempt, String key);

    /** 从本人会话的服务端历史分叉，保留来源信息。 */
    WorkspaceVO fork(UserContext actor, Long tenant, Long id, ForkReqDTO body);

    /** 将指定历史预设版本追加为新版本，既有历史不改写。 */
    WorkspaceVO restore(UserContext actor, Long tenant, Long id, int version);
}
