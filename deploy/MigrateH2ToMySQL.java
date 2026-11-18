import java.sql.*;
import java.util.*;

/**
 * H2 → MySQL 一次性迁移（方案B）：只读打开 H2 文件库，逐表 JDBC 直转，序列当前值回填 sequences 表。
 * 可重复执行（目标表先 TRUNCATE）。H2 文件不做任何修改。
 * 用法（仓库根目录，先 source ~/.config/ride-pro/docker.env 并 export）：
 *   java -cp <h2.jar>:<mysql-connector-j.jar> deploy/MigrateH2ToMySQL.java
 */
public class MigrateH2ToMySQL {

    static final Map<String, String[]> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("passenger", new String[]{"passenger"});
        TABLES.put("driver", new String[]{"driver"});
        TABLES.put("order", new String[]{"ride_order", "idempotent_record", "order_rating"});
        TABLES.put("payment", new String[]{"wallet", "ledger_entry", "idempotent_record", "pay_outbox",
                "withdraw_order", "refund_outbox", "payment_pro_account", "mirror_outbox", "payment_pro_notify"});
        TABLES.put("location", new String[]{"driver_location", "trip_track"});
    }

    public static void main(String[] args) throws Exception {
        String host = env("DB_HOST", "127.0.0.1");
        String port = env("DB_PORT", "3306");
        String user = env("DB_USER", "ride");
        String password = env("DB_PASSWORD", "");
        boolean ok = true;

        for (Map.Entry<String, String[]> e : TABLES.entrySet()) {
            String domain = e.getKey();
            String h2Url = "jdbc:h2:file:./data/" + domain + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;ACCESS_MODE_DATA=r";
            String myUrl = "jdbc:mysql://" + host + ":" + port + "/ride_" + domain
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";
            try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
                 Connection my = DriverManager.getConnection(myUrl, user, password)) {
                my.setAutoCommit(false);
                System.out.println("== ride_" + domain + " ==");
                for (String table : e.getValue()) {
                    long moved = copyTable(h2, my, table);
                    long src = count(h2, "SELECT COUNT(*) FROM " + table);
                    long dst = count(my, "SELECT COUNT(*) FROM " + table);
                    boolean match = src == dst && src == moved;
                    ok &= match;
                    System.out.printf("  %-22s H2=%-6d MySQL=%-6d %s%n", table, src, dst, match ? "OK" : "MISMATCH!");
                }
                // H2 的 CURRENT VALUE FOR 是会话级的，跨会话取 base_value（下一个待分配值，默认 cache=1）减步长 = 最后已发值。
                try (Statement s = h2.createStatement();
                     ResultSet rs = s.executeQuery("SELECT sequence_name, base_value, increment FROM information_schema.sequences")) {
                    while (rs.next()) {
                        String seq = rs.getString(1).toLowerCase();
                        long cur = Math.max(0, rs.getLong(2) - rs.getLong(3));
                        try (PreparedStatement ps = my.prepareStatement("UPDATE sequences SET val=? WHERE name=?")) {
                            ps.setLong(1, cur);
                            ps.setString(2, seq);
                            ps.executeUpdate();
                        }
                        System.out.printf("  sequence %-16s val=%d%n", seq, cur);
                    }
                }
                my.commit();
            }
        }
        System.out.println(ok ? "==> 迁移完成，行数全部一致" : "!! 存在行数不一致，请检查");
        if (!ok) System.exit(1);
    }

    static long copyTable(Connection h2, Connection my, String table) throws SQLException {
        try (Statement s = h2.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder cols = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1) { cols.append(','); marks.append(','); }
                cols.append(md.getColumnName(i));
                marks.append('?');
            }
            try (Statement trunc = my.createStatement()) {
                trunc.executeUpdate("TRUNCATE TABLE " + table);
            }
            String sql = "INSERT INTO " + table + "(" + cols + ") VALUES(" + marks + ")";
            long rows = 0;
            try (PreparedStatement ps = my.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 1; i <= n; i++) {
                        if (md.getColumnType(i) == Types.CLOB) {
                            ps.setString(i, rs.getString(i));
                        } else {
                            ps.setObject(i, rs.getObject(i));
                        }
                    }
                    ps.addBatch();
                    if (++rows % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return rows;
        }
    }

    static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}