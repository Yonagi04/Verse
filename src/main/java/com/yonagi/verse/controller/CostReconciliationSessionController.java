package com.yonagi.verse.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.common.security.CostReconciliationSessionRoutes;
import com.yonagi.verse.service.LoginSessionValidationService.Identity;
import com.yonagi.verse.service.budget.CostReconciliationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** 只在固定的主站与补账源之间签发和兑换短时登录交接。 */
@RestController
@RequestMapping(CostReconciliationSessionRoutes.BASE_PATH)
public class CostReconciliationSessionController {
    private static final int MAX_BODY_BYTES = 512;

    private final CostReconciliationSessionService sessions;
    private final ObjectMapper json;
    private final String mainOrigin;
    private final String reconciliationOrigin;

    public CostReconciliationSessionController(CostReconciliationSessionService sessions, ObjectMapper json,
            @Value("${verse.frontend-baseurl}") String mainOrigin,
            @Value("${verse.llm.cost-reconciliation.frontend-origin:http://reconciliation.localhost:3000}")
            String reconciliationOrigin) {
        this.sessions = sessions;
        this.json = json;
        this.mainOrigin = requireConfiguredOrigin(mainOrigin, "localhost");
        this.reconciliationOrigin = requireConfiguredOrigin(reconciliationOrigin, "reconciliation.localhost");
        if (this.mainOrigin.equals(this.reconciliationOrigin)) {
            throw new IllegalArgumentException("补账源必须与主站源分离");
        }
    }

    /** 在主站通过当前有效会话签发交接码，目标地址由服务端配置生成。 */
    @PostMapping(path = "/handoffs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Handoff> issue(HttpServletRequest request,
            @RequestAttribute(CostReconciliationSessionRoutes.PARENT_IDENTITY_ATTRIBUTE) Identity parent)
            throws IOException {
        requireOrigin(request, mainOrigin);
        HandoffRequest body = readBody(request, HandoffRequest.class);
        String code = sessions.issue(parent, body.batchCode(), body.challenge());
        return Results.success(new Handoff(code, reconciliationOrigin + "/" + body.batchCode()));
    }

    /** 补账页以一次性码与本标签页 verifier 兑换批次凭证。 */
    @PostMapping(path = "/exchanges", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<CostReconciliationSessionService.Exchange> exchange(HttpServletRequest request) throws IOException {
        requireOrigin(request, reconciliationOrigin);
        ExchangeRequest body = readBody(request, ExchangeRequest.class);
        return Results.success(sessions.exchange(body.batchCode(), body.code(), body.verifier()));
    }

    /** 交接拒绝以 HTTP 状态区分来源、限频和失效，响应仍使用 Verse 封装。 */
    @ExceptionHandler(ClientException.class)
    public ResponseEntity<Result<Void>> rejected(ClientException exception) {
        String code = exception.getErrorCode();
        int status = 400;
        if (CostReconciliationErrorCode.HANDOFF_ORIGIN_DENIED.code().equals(code)) {
            status = 403;
        } else if (CostReconciliationErrorCode.HANDOFF_RATE_LIMITED.code().equals(code)) {
            status = 429;
        } else if (BaseErrorCode.TOKEN_INVALID.code().equals(code) || BaseErrorCode.TOKEN_EXPIRED.code().equals(code)) {
            status = 401;
        }
        return ResponseEntity.status(status).body(Results.failure(exception));
    }

    private <T> T readBody(HttpServletRequest request, Class<T> type) throws IOException {
        // 交接请求只包含固定长度字段，读取上限也覆盖分块传输。
        byte[] payload = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (payload.length > MAX_BODY_BYTES) {
            throw new ClientException(BaseErrorCode.CLIENT_ERROR);
        }
        try {
            T body = json.readerFor(type).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload);
            if (body == null) {
                throw new ClientException(BaseErrorCode.CLIENT_ERROR);
            }
            return body;
        } catch (JsonProcessingException exception) {
            throw new ClientException(BaseErrorCode.CLIENT_ERROR);
        }
    }

    private static void requireOrigin(HttpServletRequest request, String origin) {
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN))) {
            throw new ClientException(CostReconciliationErrorCode.HANDOFF_ORIGIN_DENIED);
        }
    }

    private static String requireConfiguredOrigin(String value, String localHost) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean secure = "https".equals(scheme);
        if (host == null || !(secure || ("http".equals(scheme) && localHost.equals(host)))) {
            throw new IllegalArgumentException("补账登录需要规范的 HTTPS 源或本地开发源");
        }
        int port = uri.getPort();
        int defaultPort = secure ? 443 : 80;
        String origin = scheme + "://" + host.toLowerCase(Locale.ROOT);
        if (port > 0 && port != defaultPort) {
            origin += ":" + port;
        }
        // 和浏览器 Origin 的序列化一致，拒绝路径、查询、用户信息及显式默认端口。
        if (port > 65535 || !origin.equals(value)) {
            throw new IllegalArgumentException("补账登录需要规范的 HTTPS 源或本地开发源");
        }
        return origin;
    }

    private record HandoffRequest(
            /** 补账批次码。 */ String batchCode,
            /** 发起标签页 verifier 的 S256 摘要。 */ String challenge) {
    }

    private record ExchangeRequest(
            /** 补账批次码。 */ String batchCode,
            /** 待原子消费的一次性码。 */ String code,
            /** 仅由发起补账的标签页持有的随机证明。 */ String verifier) {
        @Override
        public String toString() {
            return "ExchangeRequest[batchCode=" + batchCode + "]";
        }
    }

    /** 主站签发成功后，浏览器使用配置生成的批次地址完成续接。 */
    public record Handoff(
            /** 绑定批次与挑战值的一次性码。 */ String code,
            /** 服务端固定源下的原批次链接。 */ String target) {
        @Override
        public String toString() {
            return "Handoff[target=" + target + "]";
        }
    }
}
