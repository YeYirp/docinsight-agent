package com.example.docinsight.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** SQLite 文件库、FTS5 表、vec0 虚拟表和扩展生命周期。 */
@Configuration
@EnableTransactionManagement
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${app.database.vec-extension:}") String vecExtension,
            @Value("${app.database.pool-size:4}") int poolSize) throws IOException {
        Path databaseFile = resolveDatabaseFile(url);
        Path extension = SqliteExtensionLoader.resolve(databaseFile.getParent(), vecExtension);
        HikariConfig config = new HikariConfig();
        config.setPoolName("docinsight-sqlite");
        config.setDataSource(new SqliteDataSource(url, extension));
        config.setMaximumPoolSize(Math.max(1, poolSize));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(3_000);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public InitializingBean databaseSchemaInitializer(
            DataSource dataSource,
            JdbcTemplate jdbc,
            AiProperties properties) {
        return () -> SqliteSchema.initialize(dataSource, jdbc,
                properties.embedding().dimension(), properties.embedding().model());
    }

    private Path resolveDatabaseFile(String url) throws IOException {
        String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix)) {
            throw new IllegalArgumentException("DATABASE_URL 必须是 SQLite JDBC URL");
        }
        String raw = url.substring(prefix.length());
        int query = raw.indexOf('?');
        if (query >= 0) {
            raw = raw.substring(0, query);
        }
        if (raw.isBlank() || ":memory:".equals(raw)) {
            throw new IllegalArgumentException("项目数据库必须使用文件路径，不能使用内存数据库");
        }
        Path databaseFile = Path.of(raw).toAbsolutePath().normalize();
        if (databaseFile.getParent() != null) {
            Files.createDirectories(databaseFile.getParent());
        }
        return databaseFile;
    }
}
