package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.validation.BindException;
import org.springframework.dao.DuplicateKeyException;

/** HTTP映射局限于Skill控制器，不将协议状态放进领域异常。 */
@RestControllerAdvice(basePackages = "com.yonagi.verse.controller.skillhub")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SkillExceptionAdvice {
    /** @return 数据库唯一约束冲突，隐藏SQL、账号及其他内部字段 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> duplicate() {
        return ResponseEntity.status(409).body(Results.failure(SkillErrorCodeEnum.CONFLICT.code(), "公开标识或命令已存在"));
    }

    /**
     * @return 已匹配本域资源的HTTP方法错误
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed() {
        return ResponseEntity.status(405).body(Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(), "请求方法不支持"));
    }

    /**
     * @return 类型不匹配，不能把协议错误伪装成依赖故障
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedType() {
        return ResponseEntity.status(415).body(Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(), "请求内容类型不支持"));
    }

    /**
     * @param exception 稳定领域错误
     * @return 对应HTTP拒绝语义及可展示原因
     */
    @ExceptionHandler(ClientException.class)
    public ResponseEntity<Result<Void>> client(ClientException exception) {
        int status = 400;
        String code = exception.getErrorCode();
        if (SkillErrorCodeEnum.NOT_ACCESSIBLE.code().equals(code)
                || SkillErrorCodeEnum.VERSION_UNAVAILABLE.code().equals(code)) {
            status = 404;
        } else if (SkillErrorCodeEnum.CONFLICT.code().equals(code)
                || SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT.code().equals(code)) {
            status = 409;
        } else if (SkillErrorCodeEnum.LIMIT_EXCEEDED.code().equals(code)) {
            status = 413;
        } else if (SkillErrorCodeEnum.RATE_LIMITED.code().equals(code)) {
            return ResponseEntity.status(429).header("Retry-After", "60").body(Results.failure(exception));
        }
        return ResponseEntity.status(status).body(Results.failure(exception));
    }

    /**
     * @param exception 已转换的依赖错误
     * @return 可重试的503，不返回底层异常内容
     */
    @ExceptionHandler(ServerException.class)
    public ResponseEntity<Result<Void>> server(ServerException exception) {
        log.error("Skill依赖失败: code={}, causeType={}, stack={}", exception.getErrorCode(),
                exception.getCause() == null ? "none" : exception.getCause().getClass().getSimpleName(),
                java.util.Arrays.stream(exception.getStackTrace()).limit(12).toList());
        return ResponseEntity.status(503).body(Results.failure(exception));
    }

    /**
     * @param exception 绑定或校验失败
     * @return 有界字段提示，不包含原始请求体
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    public ResponseEntity<Result<Void>> invalid(Exception exception) {
        String message = "请检查请求字段、格式和长度";
        if (exception instanceof BindException binding && binding.getBindingResult().getFieldError() != null) {
            message = binding.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(), message));
    }

    /** @return 请求大小超限的413错误契约 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> oversized() {
        return ResponseEntity.status(413).body(Results.failure(SkillErrorCodeEnum.LIMIT_EXCEEDED.code(),
                SkillErrorCodeEnum.LIMIT_EXCEEDED.message()));
    }

    /**
     * @param exception 未预期请求失败，仅记录脱敏类型和调用位置
     * @param response 下载已开始时禁止追加JSON，防止损坏ZIP响应
     * @return 尚未提交响应时的安全错误；已提交时不写入额外内容
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception, HttpServletResponse response) {
        log.error("Skill请求失败: type={}, stack={}", exception.getClass().getSimpleName(),
                java.util.Arrays.stream(exception.getStackTrace()).limit(12).toList());
        if (response.isCommitted()) {
            return null;
        }
        if (exception instanceof AbstractException business) {
            return ResponseEntity.status(500).body(Results.failure(business));
        }
        return ResponseEntity.status(503).body(Results.failure(SkillErrorCodeEnum.UNAVAILABLE.code(),
                SkillErrorCodeEnum.UNAVAILABLE.message()));
    }
}
