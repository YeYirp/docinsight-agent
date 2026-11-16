package com.example.docinsight.config;

import org.sqlite.SQLiteConfig;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** 每条 SQLite 连接都会启用外键并加载 sqlite-vec。 */
public class SqliteDataSource extends AbstractDataSource {

    private final String url;
    private final Path extension;

    public SqliteDataSource(String url, Path extension) {
        this.url = url;
        this.extension = extension;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return openConnection();
    }

    private Connection openConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        config.setBusyTimeout(5_000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

        Connection connection = DriverManager.getConnection(url, config.toProperties());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            SqliteExtensionLoader.load(connection, extension);
            return connection;
        } catch (Exception error) {
            connection.close();
            throw new SQLException("SQLite 或 sqlite-vec 初始化失败", error);
        }
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // DriverManager 不使用外部日志 writer
    }

    @Override
    public void setLoginTimeout(int seconds) {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

}
