package com.yonagi.verse.common.enums;

/** 调用状态与最终送达状态不同，未完成记录必须按不确定请求核查。 */
public enum MessageSubmissionStatus { QUEUED, SUBMITTING, ACCEPTED, REJECTED, UNKNOWN, EXPIRED }
