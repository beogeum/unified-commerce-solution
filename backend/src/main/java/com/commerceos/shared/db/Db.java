package com.commerceos.shared.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

/** JDBC 변환 도우미. DB 시간은 UTC(timestamptz). */
public final class Db {
    private Db() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** PostgreSQL uuid[] 파라미터. `= any(:ids)` 에 사용하며 빈 집합이면 아무 행도 일치하지 않는다. */
    public static Object uuids(Collection<UUID> ids) {
        UUID[] values = ids.toArray(new UUID[0]);
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf("uuid", values);
            }
        };
    }
}
