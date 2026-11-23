package com.judgex.repo;

import com.judgex.config.MyBatisConfig;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-MySQL integration test for the compare-and-set claim. Deliberately named *IT so the
 * default (hermetic) surefire run never touches a database; run it with
 * {@code mvn -o test -Dtest=TaskClaimMySqlIT}. Needs the local MySQL from the project setup.
 */
class TaskClaimMySqlIT {

    private static final String HOST = "127.0.0.1:3306";
    private static final String PARAMS =
            "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String ADMIN_URL = "jdbc:mysql://" + HOST + "/" + PARAMS;
    private static final String TEST_DB = "judgex_test";
    private static final String TEST_URL = "jdbc:mysql://" + HOST + "/" + TEST_DB + PARAMS;

    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory factory;
    private static long taskId;

    @BeforeAll
    static void createDatabaseAndFixtures() throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + TEST_DB);
            st.execute("CREATE DATABASE " + TEST_DB
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }

        dataSource = new DriverManagerDataSource(TEST_URL, "root", "123456");
        factory = new MyBatisConfig().sqlSessionFactory(dataSource);

        try (Connection c = dataSource.getConnection()) {
            executeSchema(c);
            long userId = insertAndReturnKey(c,
                    "INSERT INTO users (username, password_hash, display_name, role)"
                            + " VALUES ('it_user', 'x', 'IT User', 'USER')");
            long problemId = insertAndReturnKey(c,
                    "INSERT INTO problems (slug, title, difficulty, statement)"
                            + " VALUES ('it-problem', 'IT Problem', 'EASY', '')");
            long submissionId = insertAndReturnKey(c,
                    "INSERT INTO submissions (user_id, problem_id, language, source_code, status)"
                            + " VALUES (" + userId + ", " + problemId + ", 'python', 'print(1)', 'QUEUED')");
            taskId = insertAndReturnKey(c,
                    "INSERT INTO judge_tasks (submission_id, status) VALUES (" + submissionId + ", 'QUEUED')");
        }
    }

    @AfterAll
    static void dropDatabase() {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + TEST_DB);
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }

    @Test
    @DisplayName("8 个线程并发 tryClaim 同一条 QUEUED 任务，恰好 1 个成功且 worker_id 对应")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        int workers = 8;
        long startedAt = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            String workerId = "w-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try (SqlSession session = factory.openSession(true)) {
                    boolean won = session.getMapper(TaskMapper.class).tryClaim(taskId, workerId, 45);
                    return won ? workerId : null;
                }
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<String> winners = new ArrayList<>();
        for (Future<String> future : futures) {
            String winner = future.get(5, TimeUnit.SECONDS);
            if (winner != null) {
                winners.add(winner);
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(winners).hasSize(1);
        assertThat(elapsedMs).isLessThan(5000L);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT worker_id, status, attempts FROM judge_tasks WHERE id = ?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("worker_id")).isEqualTo(winners.get(0));
                assertThat(rs.getString("status")).isEqualTo("ASSIGNED");
                assertThat(rs.getInt("attempts")).isEqualTo(1);
            }
        }
    }

    private static Connection adminConnection() throws SQLException {
        try {
            return DriverManager.getConnection(ADMIN_URL, "root", "123456");
        } catch (SQLException rootFailed) {
            return DriverManager.getConnection(ADMIN_URL, "judgex", "judgex");
        }
    }

    private static void executeSchema(Connection c) throws Exception {
        String sql;
        try (InputStream in = TaskClaimMySqlIT.class.getResourceAsStream("/db/migration/V1__init.sql")) {
            assertThat(in).as("V1__init.sql on test classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (sql.startsWith("\uFEFF")) {
            sql = sql.substring(1);
        }
        StringBuilder ddl = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                ddl.append(line).append('\n');
            }
        }
        for (String statement : ddl.toString().split(";")) {
            if (!statement.isBlank()) {
                try (Statement st = c.createStatement()) {
                    st.execute(statement);
                }
            }
        }
    }

    private static long insertAndReturnKey(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }
}
