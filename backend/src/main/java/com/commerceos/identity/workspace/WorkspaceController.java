package com.commerceos.identity.workspace;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.web.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    private final JdbcClient jdbc;
    private final AccessGuard guard;

    public WorkspaceController(JdbcClient jdbc, AccessGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record WorkspaceView(UUID id, String code, String name, String status, Instant createdAt) {
    }

    @GetMapping("/current")
    public ApiResponse<WorkspaceView> current() {
        CurrentActor a = guard.actor();
        return ApiResponse.of(jdbc.sql("select * from workspace where id = :id").param("id", a.workspaceId())
                .query((rs, i) -> new WorkspaceView(Db.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("status"), Db.instant(rs, "created_at"))).single());
    }
}
