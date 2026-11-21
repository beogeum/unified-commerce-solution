package com.commerceos.platform.job;

import com.commerceos.shared.db.Db;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
    private final JdbcClient jdbc;
    private final Json json;

    public JobService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record NewJob(UUID workspaceId, String jobType, Map<String, Object> payload, UUID businessEntityId,
                         UUID channelAccountId, UUID connectionId, UUID createdByMemberId, int maxAttempts) {
    }

    /** 요청 트랜잭션 안에서 작업을 등록한다(업무 데이터와 원자적). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(NewJob j) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into async_job (id, workspace_id, business_entity_id, channel_account_id, connection_id, job_type,
                    payload, max_attempts, created_by_member_id)
                values (:id, :ws, :be, :ca, :conn, :type, cast(:payload as jsonb), :max, :member)
                """)
                .param("id", id).param("ws", j.workspaceId()).param("be", j.businessEntityId())
                .param("ca", j.channelAccountId()).param("conn", j.connectionId()).param("type", j.jobType())
                .param("payload", json.write(j.payload() == null ? Map.of() : j.payload()))
                .param("max", j.maxAttempts()).param("member", j.createdByMemberId())
                .update();
        return id;
    }

    public Optional<Job> find(UUID workspaceId, UUID id) {
        return jdbc.sql("select * from async_job where workspace_id = :ws and id = :id")
                .param("ws", workspaceId).param("id", id)
                .query(this::map).optional();
    }

    Job map(ResultSet rs, int i) throws SQLException {
        return new Job(Db.uuid(rs, "id"), Db.uuid(rs, "workspace_id"), Db.uuid(rs, "business_entity_id"),
                Db.uuid(rs, "channel_account_id"), Db.uuid(rs, "connection_id"), rs.getString("job_type"),
                JobStatus.valueOf(rs.getString("status")), json.read(rs.getString("payload")), json.read(rs.getString("result")),
                rs.getString("error_code"), rs.getString("error_message_ko"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                Db.instant(rs, "available_at"), (Integer) rs.getObject("progress_total"), (Integer) rs.getObject("progress_done"),
                Db.uuid(rs, "created_by_member_id"), Db.instant(rs, "created_at"), Db.instant(rs, "started_at"),
                Db.instant(rs, "finished_at"));
    }
}
