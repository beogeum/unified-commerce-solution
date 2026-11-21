package com.commerceos.platform.idempotency;

import com.commerceos.shared.error.Errors;
import com.commerceos.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Idempotency-Key 처리(DEC-F12). 기록 삽입·업무 처리·응답 저장을 한 트랜잭션에서 수행한다.
 * 동시에 같은 키가 들어오면 두 번째 요청은 첫 트랜잭션이 끝날 때까지 기다린 뒤 저장된 응답을 재생한다.
 * 업무 처리 실패(예외)는 기록까지 롤백되므로 같은 키로 다시 시도할 수 있다.
 */
@Service
public class IdempotencyService {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,200}$");
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final JdbcClient jdbc;
    private final Json json;
    private final Duration retention;

    public IdempotencyService(JdbcClient jdbc, Json json, @Value("${commerce.idempotency.retention:PT24H}") Duration retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.retention = retention;
    }

    @Transactional
    public ResponseEntity<?> execute(UUID workspaceId, UUID memberId, String key, String method, String path,
                                     Object requestBody, Supplier<ResponseEntity<?>> action) {
        if (key == null || key.isBlank()) {
            throw Errors.idempotencyKeyRequired();
        }
        if (!KEY.matcher(key).matches()) {
            throw Errors.validation("Idempotency-Key", "Idempotency-Key 는 8~200자의 영문·숫자·._:- 만 사용할 수 있습니다.");
        }
        byte[] hash = hash(method, path, requestBody);
        // 만료된 키는 새 요청으로 취급
        jdbc.sql("delete from idempotency_record where workspace_id = :ws and member_id = :m and idem_key = :k and expires_at < now()")
                .param("ws", workspaceId).param("m", memberId).param("k", key).update();
        int inserted = jdbc.sql("""
                insert into idempotency_record (workspace_id, member_id, idem_key, request_method, request_path, request_hash,
                    response_status, expires_at)
                values (:ws, :m, :k, :method, :path, :hash, 0, now() + make_interval(secs => :ttl))
                on conflict do nothing
                """)
                .param("ws", workspaceId).param("m", memberId).param("k", key).param("method", method)
                .param("path", path).param("hash", hash).param("ttl", (double) retention.toSeconds())
                .update();
        if (inserted == 0) {
            return replay(workspaceId, memberId, key, hash);
        }
        ResponseEntity<?> response = action.get();
        jdbc.sql("""
                update idempotency_record set response_status = :status, response_body = cast(:body as jsonb)
                 where workspace_id = :ws and member_id = :m and idem_key = :k
                """)
                .param("status", response.getStatusCode().value()).param("body", json.write(response.getBody()))
                .param("ws", workspaceId).param("m", memberId).param("k", key).update();
        return response;
    }

    private ResponseEntity<?> replay(UUID workspaceId, UUID memberId, String key, byte[] hash) {
        record Stored(byte[] hash, int status, String body) {
        }
        Optional<Stored> stored = jdbc.sql("""
                select request_hash, response_status, response_body::text as body from idempotency_record
                 where workspace_id = :ws and member_id = :m and idem_key = :k
                """)
                .param("ws", workspaceId).param("m", memberId).param("k", key)
                .query((rs, i) -> new Stored(rs.getBytes("request_hash"), rs.getInt("response_status"), rs.getString("body")))
                .optional();
        if (stored.isEmpty()) {
            throw Errors.conflict("IDEMPOTENCY_IN_PROGRESS", "같은 요청이 처리 중입니다. 잠시 후 결과를 확인하세요.");
        }
        if (!Arrays.equals(stored.get().hash(), hash)) {
            throw Errors.unprocessable("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 Idempotency-Key 입니다. 새 키로 요청하세요.");
        }
        JsonNode body = json.read(stored.get().body());
        return ResponseEntity.status(stored.get().status()).header(REPLAY_HEADER, "true").body(body);
    }

    private byte[] hash(String method, String path, Object body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            String canonical = body == null ? "" : json.write(json.mapper().valueToTree(body));
            md.update(canonical.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
