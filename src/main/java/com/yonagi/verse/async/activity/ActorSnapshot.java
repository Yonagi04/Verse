package com.yonagi.verse.async.activity;

/** 动态操作人的最小安全快照。 */
public record ActorSnapshot(Long userId, String username, String nickname) {}
