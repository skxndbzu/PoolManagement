package com.poolguard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;

/** 全批次持有会话级锁，进程退出时 PostgreSQL 自动释放；演示环境使用本地锁。 */
@Component
public class OperationLock {
    private static final long KEY = 791_002_409L;
    private final DataSource dataSource;
    private final boolean demo;
    private final Semaphore local = new Semaphore(1);
    public OperationLock(DataSource dataSource, @Value("${poolguard.demo:false}") boolean demo) {
        this.dataSource = dataSource; this.demo = demo;
    }
    public Lease acquire() {
        if (demo) {
            if (!local.tryAcquire()) throw busy();
            return new Lease(null);
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, KEY);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getBoolean(1)) return new Lease(connection);
                }
            }
            connection.close();
            throw busy();
        } catch (SQLException e) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法获取数据库任务锁");
        }
    }
    private ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "已有检测或账号操作正在执行，请稍后重试");
    }
    public class Lease implements AutoCloseable {
        private final Connection connection;
        private boolean closed;
        Lease(Connection connection) { this.connection = connection; }
        public void assertHeld() {
            if (closed) throw new IllegalStateException("任务锁已释放");
            if (connection != null) try {
                if (!connection.isValid(3)) throw new SQLException();
            } catch (SQLException e) { throw new IllegalStateException("数据库任务锁连接丢失，停止远程动作"); }
        }
        public void close() {
            if (closed) return;
            closed = true;
            if (connection == null) { local.release(); return; }
            try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, KEY); statement.execute();
            } catch (SQLException e) {
                try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
            } finally { try { connection.close(); } catch (SQLException ignored) { } }
        }
    }
}
