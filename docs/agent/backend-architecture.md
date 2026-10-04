# 后端架构与职责边界

## 依赖方向

常规业务请求遵循：

```text
Controller -> Application/Service -> Domain policy / Port -> Mapper or Adapter
```

- `controller`：请求绑定、Bean Validation、认证上下文接入、调用用例、返回响应。
- `service`：用例编排、业务不变量、权限判断和事务边界。
- `dao.mapper`：持久化查询与原子更新，不承载业务决策。
- `dao.entity`：数据库结构映射，不作为 HTTP 请求或响应模型。
- `service.forward` / Adapter：Provider 协议转换、能力适配和上游错误归一化。
- `async`：事件契约、发布、消费、重试、幂等和死信处理。
- `common`：真正跨领域且稳定的基础能力，不能成为无归属业务逻辑的收容所。

低层模块不得反向依赖 Controller。跨领域协作通过公开 Service/Port 完成，不直接操作对方 Mapper 或内部对象。

## Controller 基准

- Controller 保持薄：不得编写查询、事务、缓存、Provider 选择或复杂业务分支。
- 使用请求/响应 DTO，边界输入使用 Jakarta Validation。
- 身份信息取自可信认证上下文，不能相信请求体中的 userId/tenantId 所有权声明。
- Controller 不捕获通用异常转换成功结果；统一交给 `GlobalExceptionHandler`。
- 文件上传、流式响应和协议透传需显式设置媒体类型、大小限制、取消与异常行为。

## Service 与领域逻辑

- Service 以用例命名和组织，避免演变成包含整个领域所有行为的 God Service。
- 事务放在 Service 用例边界；事务方法应明确读写范围和锁/并发策略。
- 复杂且可独立测试的判断提取为领域策略或值对象，不塞入 Controller、Mapper 或 Util。
- 不以 `ServiceImpl` 继承关系泄漏持久化能力；调用者依赖业务方法，而不是通用 CRUD 细节。
- 接口只在存在边界、多个实现或测试替身价值时创建，不能为了形式为每个类建立一一对应接口。

## LLM Provider 边界

稳定流程应为：

```text
Controller -> Forward use case -> Model/route resolver -> Capability adapter -> Provider
```

Provider Adapter 负责：

- 项目请求与上游请求的互转；
- 上游响应与项目响应的互转；
- Provider 特有鉴权、协议和错误映射；
- 声明并实现真实支持的模型能力。

Provider Adapter 不负责全局租户权限、计费策略、通用缓存、数据库事务或全局路由。重试必须判断幂等性和重复计费风险。

## 事务、外部调用与异步

- 不在长数据库事务中调用 LLM、S3、OAuth 或其他远程系统；确需如此必须说明一致性理由和超时风险。
- 跨数据库与消息的一致性优先沿用现有 Outbox 机制，不自行发明“双写后补偿”。
- 消费者默认可能重复收到消息，必须设计幂等键、原子状态转换和可观察的失败处理。
- 异步任务不得依赖请求线程中的 ThreadLocal 上下文；必要身份和租户快照进入明确事件契约。
- 定时任务和重试必须有界，支持并发抢占保护，并暴露失败、积压和延迟指标。

## 外部系统基准

每个外部调用应明确：连接/响应超时、可重试错误、最大重试、熔断或限流、降级、取消、资源释放和监控。日志必须脱敏 Authorization、API Key、隐私字段和可能含敏感信息的完整 Prompt/Response。
