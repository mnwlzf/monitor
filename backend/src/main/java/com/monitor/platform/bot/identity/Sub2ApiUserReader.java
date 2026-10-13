package com.monitor.platform.bot.identity;

import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直连 Sub2API 只读库读取平台用户。
 *
 * <p>复用 {@code monitor.pool.ingest.*} 里已经配好的只读连接信息（与号池增量采集同一个库），
 * 但不依赖 {@code enabled} 开关：关掉增量采集时，身份识别仍然可用。</p>
 *
 * <p>连接池统一由 {@link Sub2ReadOnlyJdbc} 提供，这里只负责取数。</p>
 */
@Component
public class Sub2ApiUserReader {

    /**
     * 只取识别身份需要的列。
     *
     * <p>{@code deleted_at} 是软删除标记，必须过滤：否则已注销用户仍会被当成平台用户。
     * 密码哈希、TOTP 密钥等敏感列一律不取。</p>
     */
    private static final String SELECT_USERS = """
            SELECT id, email, username, role, status
            FROM public.users
            WHERE deleted_at IS NULL
            ORDER BY id
            """;

    static final RowMapper<Sub2ApiUser> ROW_MAPPER = (rs, rowNum) -> new Sub2ApiUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("status")
    );

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2ApiUserReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 是否配置了可用的只读库连接。 */
    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 读取全部未删除的平台用户。 */
    public List<Sub2ApiUser> fetchUsers() {
        return jdbc.template().query(SELECT_USERS, ROW_MAPPER);
    }
}
