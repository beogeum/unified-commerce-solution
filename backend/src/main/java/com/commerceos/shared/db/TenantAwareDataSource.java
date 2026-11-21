package com.commerceos.shared.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 연결을 받을 때 비소유 DB 역할로 전환하고 RLS 문맥(app.workspace_id, app.bypass_rls)을 설정한다.
 * 연결을 돌려줄 때는 원래 역할·빈 문맥으로 되돌려 풀의 다른 사용자(마이그레이션 등)에 남지 않게 한다.
 */
public class TenantAwareDataSource extends DelegatingDataSource {
    private final String appRole;
    private final boolean enabled;

    public TenantAwareDataSource(DataSource target, String appRole, boolean enabled) {
        super(target);
        if (!appRole.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("invalid db role name");
        }
        this.appRole = appRole;
        this.enabled = enabled;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection c) throws SQLException {
        if (!enabled) {
            return c;
        }
        UUID ws = TenantContext.workspaceId();
        try (PreparedStatement ps = c.prepareStatement(
                "select set_config('role', ?, false), set_config('app.workspace_id', ?, false), set_config('app.bypass_rls', ?, false)")) {
            ps.setString(1, appRole);
            ps.setString(2, ws == null ? "" : ws.toString());
            ps.setString(3, TenantContext.isSystem() ? "on" : "off");
            ps.execute();
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && !c.isClosed()) {
                        try (Statement st = c.createStatement()) {
                            st.execute("reset role; select set_config('app.workspace_id', '', false), set_config('app.bypass_rls', 'off', false)");
                        } catch (SQLException ignored) {
                            // 연결이 깨진 경우 풀이 폐기한다
                        }
                    }
                    try {
                        return method.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
