package local.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
final class PythonBridge {
    private static final Logger LOG = LoggerFactory.getLogger(PythonBridge.class);
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(25);
    private static final int REQUESTS_PER_PROCESS = 512;
    private static final List<String> WORKER_SETTINGS = List.of(
            "OPENAI_API_KEY", "QDRANT_API_KEY", "MCP_MEMORY_LLM_MODEL", "MCP_MEMORY_EMBEDDING_MODEL",
            "MCP_MEMORY_EMBEDDING_DIMS", "MCP_MEMORY_QDRANT_URL", "MCP_MEMORY_COLLECTION",
            "MCP_MEMORY_SCOPE", "MCP_MEMORY_SEARCH_THRESHOLD", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "PATH", "SystemRoot", "TMPDIR", "TEMP");

    private final ObjectMapper mapper;
    private final Environment environment;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> new Thread(task, "memory-worker"));
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "memory-watchdog"));
    private Worker worker;
    private boolean closed;

    PythonBridge(ObjectMapper mapper, Environment environment) {
        this.mapper = mapper;
        this.environment = environment;
        watchdog.setRemoveOnCancelPolicy(true);
    }

    JsonNode call(String operation, ObjectNode input) {
        long started = System.nanoTime();
        long deadline = started + BUDGET;
        String requestId = UUID.randomUUID().toString();
        FutureTask<JsonNode> task = new FutureTask<>(() -> exchange(operation, input, requestId, deadline));
        String result = "OK";
        try {
            requests.execute(task);
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ex) {
            result = ErrorCode.BUSY.name();
            throw new MemoryError(ErrorCode.BUSY, requestId);
        } catch (TimeoutException | InterruptedException ex) {
            task.cancel(false);
            requests.remove(task);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            result = ErrorCode.DEPENDENCY_UNAVAILABLE.name();
            throw new MemoryError(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId);
        } catch (ExecutionException ex) {
            MemoryError error = ex.getCause() instanceof MemoryError cause ? cause
                    : new MemoryError(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId);
            result = error.code.name();
            throw error;
        } finally {
            LOG.info("requestId={} operation={} durationMs={} result={}", requestId, operation,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), result);
        }
    }

    private JsonNode exchange(String operation, ObjectNode input, String requestId, long deadline) {
        if (deadline - System.nanoTime() <= 0) {
            throw new MemoryError(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId);
        }
        Worker active = ensureWorker(requestId);
        long remaining = Math.max(0, deadline - System.nanoTime());
        ScheduledFuture<?> kill = watchdog.schedule(active.process::destroyForcibly, remaining, TimeUnit.NANOSECONDS);
        try {
            ObjectNode message = mapper.createObjectNode().put("requestId", requestId)
                    .put("operation", operation).put("budgetMs", TimeUnit.NANOSECONDS.toMillis(remaining));
            message.set("input", input);
            active.writer.write(mapper.writeValueAsString(message));
            active.writer.newLine();
            active.writer.flush();
            String line = active.reader.readLine();
            if (line == null) {
                throw new IOException("Worker closed its output");
            }
            JsonNode response = mapper.readTree(line);
            if (response == null || !requestId.equals(response.path("requestId").asText())) {
                throw new IOException("Unexpected worker response");
            }
            if (response.hasNonNull("error")) {
                try {
                    throw new MemoryError(ErrorCode.valueOf(response.path("error").asText()), requestId);
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Unknown worker error code");
                }
            }
            if (!response.path("result").isObject()) {
                throw new IOException("Missing worker result");
            }
            return response.get("result");
        } catch (IOException ex) {
            stopWorker();
            throw new MemoryError(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId);
        } finally {
            kill.cancel(false);
        }
    }

    private synchronized Worker ensureWorker(String requestId) {
        if (closed) {
            throw new MemoryError(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId);
        }
        if (worker != null && worker.process.isAlive() && worker.requests++ < REQUESTS_PER_PROCESS) {
            return worker;
        }
        stopWorker();
        String executable = environment.getProperty("MCP_MEMORY_PYTHON", "");
        Path directory = null;
        try {
            if (executable.isBlank() || !Path.of(executable).isAbsolute() || !Files.isExecutable(Path.of(executable))) {
                throw new MemoryError(ErrorCode.CONFIGURATION_ERROR, requestId);
            }
            directory = Files.createTempDirectory("test-memory-mcp-");
            Path script = directory.resolve("worker.py");
            try (var resource = new ClassPathResource("adapter/worker.py").getInputStream()) {
                Files.copy(resource, script);
            }
            ProcessBuilder builder = new ProcessBuilder(executable, "-u", script.toString())
                    .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
            var childEnvironment = builder.environment();
            childEnvironment.clear();
            for (String name : WORKER_SETTINGS) {
                String value = environment.getProperty(name);
                if (value != null) {
                    childEnvironment.put(name, value);
                }
            }
            childEnvironment.put("PYTHONIOENCODING", "UTF-8");
            childEnvironment.put("MEM0_TELEMETRY", "false");
            childEnvironment.put("MEM0_DIR", directory.toString());
            worker = new Worker(builder.start(), directory);
            return worker;
        } catch (IOException | IllegalArgumentException ex) {
            deleteScratch(directory);
            throw new MemoryError(ErrorCode.CONFIGURATION_ERROR, requestId);
        }
    }

    private synchronized void stopWorker() {
        if (worker == null) {
            return;
        }
        Worker previous = worker;
        worker = null;
        previous.process.destroyForcibly();
        try {
            if (previous.process.waitFor(1, TimeUnit.SECONDS)) {
                deleteScratch(previous.directory);
            } else {
                previous.process.onExit().thenRun(() -> deleteScratch(previous.directory));
            }
        } catch (InterruptedException ex) {
            previous.process.onExit().thenRun(() -> deleteScratch(previous.directory));
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteScratch(Path directory) {
        if (directory != null) {
            try {
                FileSystemUtils.deleteRecursively(directory);
            } catch (IOException ex) {
                LOG.warn("Could not clean the worker scratch directory");
            }
        }
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        requests.shutdownNow();
        stopWorker();
        watchdog.shutdownNow();
    }

    private static final class Worker {
        final Process process;
        final Path directory;
        final BufferedReader reader;
        final BufferedWriter writer;
        int requests = 1;

        Worker(Process process, Path directory) {
            this.process = process;
            this.directory = directory;
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
    }
}
