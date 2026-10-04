# HTTP API 设计规范

本规范定义 Verse 的目标 API 结构。新增接口必须直接遵守；现有不规范路径必须纳入专项迁移，不得因为历史兼容而永久保留错误的 scope。迁移过程需保留兼容路径或采用明确的版本切换策略。

## 路径基线

- 业务 API 统一以 `/api/v1/{业务scope}` 开始。
- `{业务scope}` 表达接口真正所属的业务能力，而不是认证方式、数据过滤条件或数据库外键。使用稳定、可理解、复数、`kebab-case` 的资源名词，例如 `/api/v1/users`、`/api/v1/tenants`、`/api/v1/llm-services`、`/api/v1/playground`。
- 路径中只放资源定位所需标识；过滤、搜索、排序、分页进入 query 参数。
- Verse 允许使用 `/create`、`/update`、`/delete`、`/list`、`/info` 等明确动作路径，但同类操作的层级和命名必须一致。
- 不使用尾随 `/`；多单词路径使用 `kebab-case`，不使用 camelCase 或泄漏 Java 方法名的 `/getXxx`、`/updateXxxById`。
- Provider 协议兼容端点可以保留上游标准路径和响应格式，但必须在 Controller 注释或文档中声明为兼容性例外。

## 业务 scope 与租户边界

租户是 Verse 的安全和数据隔离边界，但不自动成为所有业务资源的 URL 父级。判断资源是否应位于 `/tenants/{tenantId}` 下时，必须区分“业务从属关系”和“仅受租户隔离”。

只有同时满足以下语义之一，才把资源嵌套到 tenant 路径：

- 接口本身管理 Tenant，例如租户资料、设置、启停和品牌信息；
- 子资源离开该 Tenant 就没有独立身份或生命周期，例如租户成员、租户邀请和加入申请；
- tenantId 是资源稳定业务标识的一部分，而不只是权限校验或查询条件。

以下情况不得使用 `/api/v1/tenants/{tenantId}/...`：

- 仅因为表中存在 `tenant_id`；
- 仅因为请求需要校验当前租户；
- 仅因为查询按租户过滤或缓存 Key 包含 tenantId；
- 业务能力有独立、清晰的一级 scope。

这类接口应使用自己的业务 scope，租户身份从可信认证/租户上下文取得，并继续在 Service、SQL 和缓存层执行隔离。例如 Playground 的核心能力是会话、工作台和模型试用，不是 Tenant 管理，其目标路径必须以 `/api/v1/playground` 开始，不能继续以 `/api/v1/tenants/{tenantId}/playground` 开始。

正确示例：

```text
POST /api/v1/playground/sessions/create
GET  /api/v1/playground/sessions/list
GET  /api/v1/playground/sessions/{sessionId}/info
POST /api/v1/playground/sessions/{sessionId}/update
POST /api/v1/playground/sessions/{sessionId}/delete
POST /api/v1/playground/sessions/{sessionId}/stop
```

Tenant 强从属资源可以嵌套：

```text
GET  /api/v1/tenants/{tenantId}/members/list
POST /api/v1/tenants/{tenantId}/members/{memberId}/remove
POST /api/v1/tenants/{tenantId}/join-requests/{requestId}/approve
```

不得为了迁就 Controller 或 Service 方法名设计路径。设计新路径前先确定业务 scope、资源身份、从属关系和租户上下文来源。

## HTTP 语义

- `GET` 只读、安全且幂等，不执行 logout、状态更新、计费或事件触发。
- `POST` 创建资源或执行非幂等领域命令。
- `PUT` 完整替换且幂等；部分更新使用 `PATCH`。
- `DELETE` 删除或撤销资源并保持幂等语义。
- 创建成功应能定位创建后的资源；异步接受与同步完成的响应语义不能混淆。
- 幂等键、乐观锁或版本字段用于有重复提交风险的关键命令，不能只依赖前端禁用按钮。

## 请求与响应

- Controller 不直接接收或返回 DO、Mapper Projection 或 Provider SDK 类型。
- DTO 字段必须有中文说明，并通过 Bean Validation 表达长度、格式、范围和必填约束。
- 普通业务接口返回 `Result<T>`，成功使用 `Results.success(...)`；禁止 Controller 自行拼装不同的包装结构。
- 分页接口统一表达 page、size、total、items，并限制最大 page size；稳定定义默认排序和空结果。
- 时间值明确时区和格式；金额、Token 数量等精度敏感值禁止使用浮点近似。
- API 不暴露堆栈、SQL、内部类名、上游密钥或未经归一化的 Provider 错误。

## 权限与租户隔离

- 每个资源接口必须说明身份主体、所需权限和资源所有权校验位置。
- 路径包含 tenantId 不代表已授权；Service 在访问数据前执行实时租户/角色校验。
- 查询和写入均带租户边界，缓存键也必须包含相同隔离维度。
- 对无权访问的资源，避免通过错误差异泄露其他租户资源是否存在。

## 兼容与迁移

- 修改路径、字段、枚举、默认值或错误语义前，先搜索所有调用方和测试。
- 除非需求明确允许破坏性升级，不删除或重解释已有字段。
- 必须对现有 Controller 路径建立完整清单，逐项标记目标 scope、租户是否为强从属关系、目标路径、调用方和迁移状态。Playground 以及其他仅受租户隔离、但被错误放在 `/tenants` 下的接口都必须迁出。
- 路径治理应作为专项迁移完成，而不是只在碰巧修改某个接口时零散处理；同时不得夹带到无关功能中造成不可控的批量破坏。
- 迁移优先新增规范路径并复用同一 Service，旧路径只作为临时兼容入口，标记弃用且不得继续扩展新能力。
- 同步迁移 `D:\Code\verse-frontend`、测试、接口文档、网关规则和其他已确认调用方；不得只修改后端 Controller。
- 兼容期同时验证新旧路径指向相同行为；调用方全部切换并完成发布观察后，按计划删除旧路径和兼容测试。
- 新接口禁止继续使用已知错误 scope，即使同一 Controller 的历史路径仍未迁移。
