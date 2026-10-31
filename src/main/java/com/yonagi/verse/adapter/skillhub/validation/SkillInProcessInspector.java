package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillPackageInspector;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 复用导入工作线程直接校验，不创建脱离工作槽位的超时后台任务。 */
@Component
public class SkillInProcessInspector implements SkillPackageInspector {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SkillPackageValidator validator = new SkillPackageValidator();

    @Override
    public SkillValidationReport inspect(byte[] archive, BooleanSupplier leaseValid) {
        SkillValidationControl control = new SkillValidationControl(() -> !closed.get() && leaseValid.getAsBoolean());
        try {
            control.check();
            SkillValidationReport result = parse(archive, control);
            control.check();
            return result;
        } catch (SkillValidationCancelledException exception) {
            throw new ServerException("Skill校验已取消或超过执行预算", SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /** 停机不再接受新解析，进行中的解析在下一检查点退出。 */
    @PreDestroy
    public void close() {
        closed.set(true);
    }

    private SkillValidationReport parse(byte[] archive, SkillValidationControl control) {
        try {
            return validator.validate(archive, control);
        } catch (SkillPackageValidationException exception) {
            return rejected(archive, exception.getCode(), control);
        } catch (IOException exception) {
            return rejected(archive, "INVALID_ARCHIVE", control);
        }
    }

    private SkillValidationReport rejected(byte[] archive, String code, SkillValidationControl control) {
        control.check();
        return new SkillValidationReport(SkillPackageValidator.hash(archive == null ? new byte[0] : archive, control),
                false, code, null, null, null, null, null, null, null, 0L, List.of());
    }
}
