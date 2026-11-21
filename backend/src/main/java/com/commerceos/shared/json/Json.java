package com.commerceos.shared.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** jsonb 컬럼 직렬화 도우미(API 와 같은 snake_case 매퍼 사용). */
@Component
public class Json {
    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public JsonNode read(String json) {
        return json == null ? null : mapper.readTree(json);
    }

    public <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
