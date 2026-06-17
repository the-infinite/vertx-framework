package io.github.the_infinite.framework.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class BatchContainerTest {

    @Test
    void testSingleThreadBatching() {
        int batchSize = 5;
        BatchContainer<Integer> container = new BatchContainer<>(batchSize);

        for (int i = 0; i < 13; i++) {
            container.add(i);
        }

        // first 5 should be available
        final var batch1Opt = container.poll();
        assertTrue(batch1Opt.isPresent());
        final var batch1 = batch1Opt.get();
        assertEquals(5, batch1.size());
        assertEquals(0, batch1.get(0));
        assertEquals(4, batch1.get(4));

        // next 5 should be available
        final var batch2Opt = container.poll();
        assertTrue(batch2Opt.isPresent());
        final var batch2 = batch2Opt.get();
        assertEquals(5, batch2.size());
        assertEquals(5, batch2.get(0));
        assertEquals(9, batch2.get(4));

        // last 3 are in active batch, not yet available
        assertFalse(container.poll().isPresent());

        // flush the rest
        container.flush();
        final var batch3Opt = container.poll();
        assertTrue(batch3Opt.isPresent());
        final var batch3 = batch3Opt.get();
        assertEquals(3, batch3.size());
        assertEquals(10, batch3.get(0));
        assertEquals(12, batch3.get(2));
    }

    @Test
    void testMultiThreadedBatching() throws InterruptedException {
        int batchSize = 10;
        int threadCount = 10;
        int itemsPerThread = 100;
        BatchContainer<Integer> container = new BatchContainer<>(batchSize);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      try {
          CountDownLatch latch = new CountDownLatch(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < itemsPerThread; j++) {
                            container.add(1);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            container.flush();

            int totalItems = 0;
            Optional<List<Integer>> batchOpt;
            while ((batchOpt = container.poll()).isPresent()) {
                final var batch = batchOpt.get();
                totalItems += batch.size();
                assertTrue(batch.size() <= batchSize);
            }

            assertEquals(threadCount * itemsPerThread, totalItems);
        } finally {
           executor.shutdown();
        }
    }
}
