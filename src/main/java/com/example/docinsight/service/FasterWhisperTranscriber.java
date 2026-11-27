package com.example.docinsight.service;

import com.example.docinsight.config.VideoProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 faster-whisper worker：模型只加载一次，视频按单线程队列依次转写。
 * GitHub: https://github.com/SYSTRAN/faster-whisper
 */
@Component
public class FasterWhisperTranscriber implements VideoTranscriber {

    private static final Logger log = LoggerFactory.getLogger(FasterWhisperTranscriber.class);
    private static final String EOF = "__EOF__";
    private static final int STDERR_LIMIT = 16_000;

    private final VideoProperties props;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final StringBuilder stderr = new StringBuilder();
    private final ScheduledExecutorService idleScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "whisper-idle");
                thread.setDaemon(true);
                return thread;
            });

    private Process process;
    private BufferedWriter stdin;
    private BlockingQueue<String> stdout;
    private Thread stdoutThread;
    private Thread stderrThread;
    private ScheduledFuture<?> idleTask;
    private long lastUsedAt;

    public FasterWhisperTranscriber(VideoProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public VideoTranscript transcribe(Path videoPath) {
        if (!props.isEnabled()) {
            throw new IllegalStateException("视频摄取已关闭");
        }
        synchronized (lock) {
            lastUsedAt = System.currentTimeMillis();
            ensureStarted();
            BlockingQueue<String> queue = stdout;
            if (queue == null) {
                stopWorker();
                throw new IllegalStateException("Whisper worker 尚未就绪");
            }
            String requestId = UUID.randomUUID().toString();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", requestId);
            request.put("path", videoPath.toAbsolutePath().normalize().toString());
            request.put("language", props.getLanguage());

            try {
                stdin.write(mapper.writeValueAsString(request));
                stdin.newLine();
                stdin.flush();
            } catch (IOException error) {
                stopWorker();
                throw new IllegalStateException("无法向 Whisper worker 发送视频任务", error);
            }

            long timeout = Math.max(60, props.getTranscriptionTimeoutMinutes()) * 60L;
            long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    stopWorker();
                    throw new IllegalStateException("视频转写超时，请缩短视频或调大 "
                            + "VIDEO_TRANSCRIBE_TIMEOUT_MINUTES");
                }

                String line;
                try {
                    line = queue.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("视频转写被中断", error);
                }
                if (line == null) {
                    if (!process.isAlive()) {
                        String detail = lastError();
                        stopWorker();
                        throw new IllegalStateException("Whisper worker 已退出" + detail);
                    }
                    continue;
                }
                if (EOF.equals(line)) {
                    String detail = lastError();
                    stopWorker();
                    throw new IllegalStateException("Whisper worker 异常退出" + detail);
                }
                VideoTranscript transcript = parseResponse(line, requestId);
                if (transcript != null) {
                    lastUsedAt = System.currentTimeMillis();
                    scheduleIdleStop();
                    return transcript;
                }
            }
        }
    }

    private void ensureStarted() {
        if (process != null && process.isAlive() && stdin != null && stdout != null) {
            return;
        }
        stopWorker();
        stderr.setLength(0);

        Path script = materializeScript();
        String python = resolvePython();
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add("-u");
        command.add(script.toString());
        command.add("--model");
        command.add(props.getModel());
        command.add("--device");
        command.add(props.getDevice());
        command.add("--compute-type");
        command.add(props.getComputeType());
        command.add("--language");
        command.add(props.getLanguage());

        log.info("启动 faster-whisper worker: model={}, device={}, computeType={}",
                props.getModel(), props.getDevice(), props.getComputeType());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("HF_HUB_DISABLE_SYMLINKS_WARNING", "1");
        Path modelDir = Path.of(props.getModelDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(modelDir);
        } catch (IOException error) {
            throw new IllegalStateException("创建视频模型目录失败", error);
        }
        builder.environment().put("HF_HOME", modelDir.toString());
        if (props.getHfEndpoint() != null && !props.getHfEndpoint().isBlank()) {
            builder.environment().put("HF_ENDPOINT", props.getHfEndpoint().trim());
        }
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IllegalStateException("启动 faster-whisper worker 失败", error);
        }

        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        stdout = queue;
        stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        stdoutThread = daemon("whisper-stdout",
                () -> pumpStdout(process.getInputStream(), queue));
        stderrThread = daemon("whisper-stderr", () -> pumpStderr(process.getErrorStream()));

        long deadline = System.nanoTime()
                + Duration.ofSeconds(Math.max(30, props.getStartupTimeoutSeconds())).toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                String detail = lastError();
                stopWorker();
                throw new IllegalStateException("Whisper 模型加载超时" + detail);
            }
            String line;
            try {
                line = queue.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stopWorker();
                throw new IllegalStateException("等待 Whisper worker 启动时被中断", error);
            }
            if (line == null) {
                if (!process.isAlive()) {
                    String detail = lastError();
                    stopWorker();
                    throw new IllegalStateException("Whisper worker 启动失败" + detail);
                }
                continue;
            }
            if (EOF.equals(line)) {
                String detail = lastError();
                stopWorker();
                throw new IllegalStateException("Whisper worker 启动失败" + detail);
            }
            try {
                JsonNode status = mapper.readTree(line);
                if ("ready".equals(status.path("status").asText())) {
                    return;
                }
                if ("error".equals(status.path("status").asText())) {
                    String detail = status.path("error").asText("未知错误");
                    stopWorker();
                    throw new IllegalStateException("Whisper 初始化失败: " + detail + lastError());
                }
            } catch (RuntimeException error) {
                log.debug("忽略 Whisper 非协议输出: {}", line);
            }
        }
    }

    private VideoTranscript parseResponse(String line, String requestId) {
        try {
            JsonNode response = mapper.readTree(line);
            if (!requestId.equals(response.path("id").asText())) {
                return null;
            }
            if (!response.path("ok").asBoolean(false)) {
                throw new IllegalStateException("视频转写失败: "
                        + response.path("error").asText("未知错误"));
            }
            List<VideoTranscript.Segment> segments = new ArrayList<>();
            for (JsonNode segment : response.path("segments")) {
                String text = segment.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                segments.add(new VideoTranscript.Segment(
                        segment.path("startSeconds").asDouble(),
                        segment.path("endSeconds").asDouble(),
                        text));
            }
            return new VideoTranscript(
                    response.path("durationSeconds").asDouble(),
                    response.path("language").asText(""),
                    segments);
        } catch (RuntimeException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("视频转写失败")) {
                throw error;
            }
            log.warn("无法解析 Whisper 输出: {}", line);
            return null;
        }
    }

    private Path materializeScript() {
        Path storage = Path.of(props.getStorageDir()).toAbsolutePath().normalize();
        Path dataDir = storage.getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : storage.getParent();
        Path target = dataDir.resolve("native/python/faster_whisper_worker.py");
        ClassPathResource resource = new ClassPathResource("python/faster_whisper_worker.py");
        if (!resource.exists()) {
            throw new IllegalStateException("应用包缺少视频转写脚本");
        }
        try {
            Files.createDirectories(target.getParent());
            if (!Files.isRegularFile(target) || Files.size(target) != resource.contentLength()) {
                try (InputStream input = resource.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return target;
        } catch (IOException error) {
            throw new IllegalStateException("释放视频转写脚本失败", error);
        }
    }

    private String resolvePython() {
        List<String> candidates = new ArrayList<>();
        if (props.getPythonCommand() != null && !props.getPythonCommand().isBlank()) {
            candidates.add(props.getPythonCommand().trim());
        } else {
            candidates.add("python3");
            candidates.add("python");
            candidates.add("py");
        }
        for (String candidate : candidates) {
            try {
                Process check = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0) {
                    return candidate;
                }
                check.destroyForcibly();
            } catch (IOException ignored) {
                // Try the next Python executable.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("未找到 Python。请安装 Python 与 faster-whisper，"
                + "或配置 VIDEO_PYTHON_COMMAND");
    }

    private void pumpStdout(InputStream stream, BlockingQueue<String> queue) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                queue.offer(line);
            }
        } catch (IOException error) {
            log.debug("Whisper stdout 读取结束", error);
        } finally {
            queue.offer(EOF);
        }
    }

    private void pumpStderr(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderr) {
                    if (stderr.length() > STDERR_LIMIT) {
                        stderr.delete(0, stderr.length() - STDERR_LIMIT);
                    }
                    stderr.append(line).append('\n');
                }
                log.debug("whisper: {}", line);
            }
        } catch (IOException error) {
            log.debug("Whisper stderr 读取结束", error);
        }
    }

    private String lastError() {
        synchronized (stderr) {
            String detail = stderr.toString().trim();
            return detail.isEmpty() ? "" : "\n" + detail;
        }
    }

    private Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stopWorker() {
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // Process may already be gone.
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    // destroyForcibly 之后必须再 waitFor，否则进程不会被回收，
                    // 反复超时会在主机上累积僵尸进程。
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        stdin = null;
        stdout = null;
        stdoutThread = null;
        stderrThread = null;
    }

    private void scheduleIdleStop() {
        if (props.getWorkerIdleMinutes() <= 0) {
            return;
        }
        if (idleTask != null) {
            idleTask.cancel(false);
        }
        idleTask = idleScheduler.schedule(() -> {
            synchronized (lock) {
                long idleMs = TimeUnit.MINUTES.toMillis(
                        Math.max(1, props.getWorkerIdleMinutes()));
                if (process != null && System.currentTimeMillis() - lastUsedAt >= idleMs) {
                    log.info("faster-whisper worker 空闲超时，释放模型内存");
                    stopWorker();
                }
            }
        }, Math.max(1, props.getWorkerIdleMinutes()), TimeUnit.MINUTES);
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            stopWorker();
        }
        idleScheduler.shutdownNow();
    }
}
