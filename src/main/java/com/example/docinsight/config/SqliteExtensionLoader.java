package com.example.docinsight.config;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** 解析、释放并加载随应用打包的 sqlite-vec 扩展。 */
public final class SqliteExtensionLoader {

    private SqliteExtensionLoader() {
    }

    public static Path resolve(Path dataDirectory, String overridePath) throws IOException {
        if (overridePath != null && !overridePath.isBlank()) {
            Path configured = Path.of(overridePath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                throw new IOException("SQLITE_VEC_EXTENSION 指向的扩展不存在: " + configured);
            }
            return configured;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !(arch.contains("amd64") || arch.contains("x86_64"))) {
            throw new IOException("当前内置扩展仅支持 Windows x86_64: os=" + os + ", arch=" + arch);
        }

        String resourcePath = "native/windows-x86_64/vec0.dll";
        Path target = dataDirectory.resolve("native/windows-x86_64/vec0.dll");
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IOException("应用包中缺少 sqlite-vec 扩展: " + resourcePath);
        }
        Files.createDirectories(target.getParent());
        if (!Files.isRegularFile(target) || Files.size(target) != resource.contentLength()) {
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    public static String load(Connection connection, Path extension) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT load_extension(?)")) {
            statement.setString(1, extension.toAbsolutePath().toString());
            statement.execute();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT vec_version()")) {
            if (!result.next()) {
                throw new IllegalStateException("sqlite-vec 已加载但没有返回版本号");
            }
            String version = result.getString(1);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("sqlite-vec 版本号为空");
            }
            return version;
        }
    }
}
