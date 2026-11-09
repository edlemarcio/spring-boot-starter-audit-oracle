package io.github.edlemarcio.audit.oracle.testsupport;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.boot.json.JsonWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Evidence is independent of JUnit's success/failure decision; no failure is swallowed. */
public final class ScenarioReport implements BeforeEachCallback, TestWatcher {
    private static final ThreadLocal<ScenarioReport> CURRENT = new ThreadLocal<>();
    private final Map<String, Object> details = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final List<Map<String, Object>> divergences = new ArrayList<>();
    private long started;

    @Override
    public void beforeEach(ExtensionContext context) {
        details.clear(); counters.clear(); trace.clear(); divergences.clear();
        started = System.nanoTime();
        details.put("scenario", context.getRequiredTestClass().getSimpleName() + "."
                + context.getRequiredTestMethod().getName() + " " + context.getDisplayName());
        details.put("tags", List.copyOf(context.getTags()));
        CURRENT.set(this);
    }

    public static ScenarioReport current() {
        ScenarioReport report = CURRENT.get();
        if (report == null) throw new IllegalStateException("ScenarioReport extension is required");
        return report;
    }

    public void detail(String key, Object value) { details.put(key, value); }
    public void count(String key) { add(key, 1); }
    public void add(String key, long value) { counters.computeIfAbsent(key, k -> new LongAdder()).add(value); }

    public synchronized void trace(Map<String, Object> event) {
        if (trace.size() < 200) trace.add(new LinkedHashMap<>(event));
        if (event.containsKey("actual")) {
            details.put("lastDivergence", new LinkedHashMap<>(event));
            divergences.add(new LinkedHashMap<>(event));
        }
    }

    @Override public void testSuccessful(ExtensionContext context) { finish("passed", null); }
    @Override public void testFailed(ExtensionContext context, Throwable cause) { finish("failed", cause); }
    @Override public void testAborted(ExtensionContext context, Throwable cause) { finish("aborted", cause); }

    private void finish(String status, Throwable error) {
        try {
            var output = new LinkedHashMap<>(details);
            output.put("status", status);
            output.put("durationSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
            var totals = new LinkedHashMap<String, Long>();
            counters.forEach((k, v) -> totals.put(k, v.sum()));
            output.put("counters", totals);
            output.put("trace", trace);
            output.put("divergences", divergences);
            if (error != null) {
                output.put("errorType", error.getClass().getName());
                output.put("error", String.valueOf(error.getMessage()));
            }
            Path directory = Path.of(System.getProperty("audit.report.dir", "target/audit-concurrency"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), JsonWriter.standard().writeToString(output));
        } catch (Exception failure) {
            // TestWatcher exceptions are only logged by JUnit. Make evidence loss visible to the runner.
            throw new IllegalStateException("Could not write audit test evidence", failure);
        } finally {
            CURRENT.remove();
        }
    }
}
