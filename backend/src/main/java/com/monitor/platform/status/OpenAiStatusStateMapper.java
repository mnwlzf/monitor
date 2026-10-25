package com.monitor.platform.status;

/**
 * OpenAI 服务状态告警状态 Mapper（全局单行）。
 */
public interface OpenAiStatusStateMapper {

    OpenAiStatusStateEntity selectState();

    int upsertState(OpenAiStatusStateEntity entity);
}