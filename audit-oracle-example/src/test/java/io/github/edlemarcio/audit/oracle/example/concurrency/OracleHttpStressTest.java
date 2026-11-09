package io.github.edlemarcio.audit.oracle.example.concurrency;

import io.github.edlemarcio.audit.oracle.testsupport.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Random;

@Tag("oracle-stress")
@ExtendWith(ScenarioReport.class)
class OracleHttpStressTest {
    @Test
    void seededHttpStressUsesTheRealSecurityProviderAndApplicationServices() throws Exception {
        int seconds = Integer.getInteger("audit.stress.seconds", 900);
        String[] seeds = System.getProperty("audit.stress.seeds", "1,42,20261108").split(",");
        ScenarioReport.current().detail("layer", "http");
        for (String seedText : seeds) {
            for (int poolSize : new int[]{1, 2, 5, 10}) {
                var random = new Random(Long.parseLong(seedText.trim()) ^ poolSize);
                ScenarioReport.current().detail("currentSeed", seedText);
                ScenarioReport.current().detail("currentPoolSize", poolSize);
                long deadline = System.nanoTime() + Duration.ofMillis(seconds * 200L / seeds.length / 4).toNanos();
                try (var harness = new HttpOracleHarness(poolSize)) {
                    harness.run((mvc, service) -> {
                        try (var work = new ConcurrentWork(32)) {
                            int batch = 0;
                            do {
                                ScenarioReport.current().detail("currentBatch", batch++);
                                var replay = new java.util.ArrayList<java.util.Map<String, Object>>();
                                for (int i = 0; i < 32; i++) {
                                    int first = random.nextInt(50), second = random.nextInt(50);
                                    replay.add(java.util.Map.of("index", i, "firstUser", first, "secondUser", second));
                                    work.submit(() -> {
                                        harness.flow(mvc, HttpOracleHarness.identity(first), HttpOracleHarness.identity(second));
                                        return null;
                                    });
                                }
                                ScenarioReport.current().detail("currentBatchOperations", replay);
                                work.join(Duration.ofSeconds(60));
                                harness.job(service);
                                harness.reconcile(true);
                            } while (System.nanoTime() < deadline);
                        }
                    });
                }
            }
        }
    }
}
