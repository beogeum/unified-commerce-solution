package com.commerceos.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 로그인한 사용자 1명의 API 클라이언트(MockMvc). */
public class Api {
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final String token;
    public final UUID userId;
    public final UUID memberId;

    public Api(MockMvc mvc, ObjectMapper mapper, String token, UUID userId, UUID memberId) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.token = token;
        this.userId = userId;
        this.memberId = memberId;
    }

    public record Res(int status, JsonNode body, MvcResult raw) {
        public JsonNode data() {
            return body.get("data");
        }

        public String errorCode() {
            return body.path("error").path("code").asString(null);
        }

        public String header(String name) {
            return raw.getResponse().getHeader(name);
        }

        public UUID id() {
            return UUID.fromString(data().get("id").asString());
        }
    }

    public Res get(String path) {
        return call(HttpMethod.GET, path, null, null, null);
    }

    public Res post(String path, Object body) {
        return call(HttpMethod.POST, path, body, "test-" + UUID.randomUUID(), null);
    }

    public Res postWithKey(String path, Object body, String idempotencyKey) {
        return call(HttpMethod.POST, path, body, idempotencyKey, null);
    }

    public Res postIfMatch(String path, Object body, int version) {
        return call(HttpMethod.POST, path, body, "test-" + UUID.randomUUID(), version);
    }

    public Res patch(String path, Object body, Integer version) {
        return call(HttpMethod.PATCH, path, body, null, version);
    }

    public Res put(String path, Object body, Integer version) {
        return call(HttpMethod.PUT, path, body, null, version);
    }

    public Res delete(String path) {
        return call(HttpMethod.DELETE, path, null, null, null);
    }

    public Res call(HttpMethod method, String path, Object body, String idemKey, Integer ifMatch) {
        MockHttpServletRequestBuilder b = request(method, path).accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", "\"" + ifMatch + "\"");
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return perform(b);
    }

    public Res perform(AbstractMockHttpServletRequestBuilder<?> b) {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            MvcResult r = mvc.perform(b).andReturn();
            String content = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            JsonNode json = content.isEmpty() || !content.trim().startsWith("{") ? mapper.createObjectNode() : mapper.readTree(content);
            return new Res(r.getResponse().getStatus(), json, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] download(String path) {
        try {
            return mvc.perform(request(HttpMethod.GET, path).header("Authorization", "Bearer " + token)).andReturn()
                    .getResponse().getContentAsByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
