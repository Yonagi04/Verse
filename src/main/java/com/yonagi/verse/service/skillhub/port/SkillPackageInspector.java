package com.yonagi.verse.service.skillhub.port;

import java.util.function.BooleanSupplier;

/** 上传内容解析边界；只做静态校验，不加载包内类或执行脚本。 */
public interface SkillPackageInspector {
    /**
     * @param archive 有界且摘要已核对的源ZIP
     * @param leaseValid 定期确认仍持有执行代次；失效时协作停止解析
     * @return 已完成校验结果；预算耗尽、取消和非预期解析错误均为执行失败
     */
    SkillValidationReport inspect(byte[] archive, BooleanSupplier leaseValid);
}
