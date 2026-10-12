package com.monitor.platform.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 库里以字符串保存的 JSON 字段的统一读写入口。
 *
 * <p>历史数据可能写坏，解析失败只记日志并返回 {@code null}，
 * 不让一个坏字段把整个接口打成 500。</p>
 */
@Component
public class JsonPayloads {

    private static final Logger log = LoggerFactory.getLogger(JsonPayloads.class);

    private final ObjectMapper objectMapper;

    public JsonPayloads(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 安全解析 JSON 文本；内容为空或格式错误时返回 null。 */
    public JsonNode read(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            log.warn("JSON 字段解析失败，返回空值", ex);
            return null;
        }
    }

    /** 新建一个空对象节点，用于往已有 JSON 里补字段。 */
    public ObjectNode newObject() {
        return objectMapper.createObjectNode();
    }
}