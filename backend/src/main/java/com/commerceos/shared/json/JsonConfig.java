package com.commerceos.shared.json;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * API JSON 규칙: snake_case, 시간은 ISO-8601 offset(Asia/Seoul). DB 는 UTC(docs/07 §1).
 */
@Configuration
public class JsonConfig {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    JsonMapperBuilderCustomizer commerceJsonCustomizer() {
        SimpleModule module = new SimpleModule("commerce-time");
        module.addSerializer(Instant.class, new KstInstantSerializer());
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .addModule(module);
    }

    static final class KstInstantSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(DISPLAY_ZONE).toOffsetDateTime()));
        }
    }
}
