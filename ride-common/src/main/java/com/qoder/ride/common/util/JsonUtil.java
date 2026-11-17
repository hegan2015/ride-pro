package com.qoder.ride.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** JSON 序列化（事件载荷、幂等结果、分支上下文）。 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtil() {
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + type.getSimpleName(), e);
        }
    }

    public static <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列表反序列化失败: " + elementType.getSimpleName(), e);
        }
    }
}