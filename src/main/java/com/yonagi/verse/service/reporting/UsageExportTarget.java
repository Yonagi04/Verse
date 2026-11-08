package com.yonagi.verse.service.reporting;

import java.io.OutputStream;

/** 有界同步导出的文件目标，由调用方管理资源和提交时机。 */
public interface UsageExportTarget {
    /** 校验完成后声明文件名，调用方从此允许写出工作簿。 */
    void prepare(String filename);

    /** 提供工作簿输出流。构建工作簿时可先获取，文件就绪后才写入。 */
    OutputStream outputStream();
}
