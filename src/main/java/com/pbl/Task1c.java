package com.pbl;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Task1c {

    private static final String FILE = "numbers.txt";
    private static final long EXPECTED_TOTAL = 50_000_000L;
    private static final int BATCH_SIZE = 10_000;
    private static final int QUEUE_CAPACITY = 100;

    private static final String[] POISON = new String[0]; // "the queue is closed"

    public static void main(String[] args) throws Exception {
        System.out.printf("%-8s %-10s %-12s %-12s %-10s %-12s %-10s %-6s%n",
                "workers", "time_ms", "negative", "positive", "zero", "total", "peak_MB", "Correctness");

        long[] first = null; // result of the first run, all others must match it
        for (int n = 1; n <= 64; n *= 2) {
            System.gc();
            resetPeakMemory();

            long t0 = System.nanoTime();
            long[] r = run(n);
            long t1 = System.nanoTime();

            double ms = (t1 - t0) / 1_000_000.0;
            double peakMb = peakHeapBytes() / (1024.0 * 1024.0);
            long total = r[0] + r[1] + r[2];

            if (first == null) first = r;
            boolean same = r[0] == first[0] && r[1] == first[1] && r[2] == first[2];
            boolean ok = same && total == EXPECTED_TOTAL;

            System.out.printf("%-8d %-10.1f %-12d %-12d %-10d %-12d %-10.1f %-6s%n",
                    n, ms, r[0], r[1], r[2], total, peakMb, ok ? "OK" : "WRONG");
        }
    }

    // One reader + n workers. Returns : negative, positive, zero
    private static long[] run(int n) throws InterruptedException {
        BlockingQueue<String[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        // One slot per worker: nobody writes where anybody else writes.
        long[] neg = new long[n];
        long[] pos = new long[n];
        long[] zero = new long[n];

        Thread reader = new Thread(() -> readFile(queue, n));

        Thread[] workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int index = i;
            workers[i] = new Thread(() -> work(queue, neg, pos, zero, index));
        }

        reader.start();
        for (Thread w : workers) w.start();

        reader.join();
        for (Thread w : workers) w.join();

        // safe only after join()
        long sn = 0, sp = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            sn += neg[i];
            sp += pos[i];
            sz += zero[i];
        }
        return new long[]{sn, sp, sz};
    }

    // The producer: the only thread that reads the file.
    private static void readFile(BlockingQueue<String[]> queue, int workers) {
        try (BufferedReader reader = new BufferedReader(new FileReader(FILE), 1 << 16)) {
            String[] batch = new String[BATCH_SIZE];
            int count = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                batch[count++] = line;
                if (count == BATCH_SIZE) {
                    queue.put(batch);                 // waits while the queue is full
                    batch = new String[BATCH_SIZE];
                    count = 0;
                }
            }
            if (count > 0)
                queue.put(Arrays.copyOf(batch, count)); // the last, shorter batch

            for (int i = 0; i < workers; i++)
                queue.put(POISON);                    // close: one stop signal per worker
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // A consumer: takes batches until it takes POISON.
    private static void work(BlockingQueue<String[]> queue,
                             long[] neg, long[] pos, long[] zero, int index) {
        long negative = 0, positive = 0, zeros = 0; // local counters

        try {
            while (true) {
                String[] batch = queue.take();        // waits while the queue is empty
                if (batch == POISON) break;

                for (String line : batch) {
                    if (line.isEmpty()) continue;
                    int v = Integer.parseInt(line);
                    if (v < 0) negative++;
                    else if (v > 0) positive++;
                    else zeros++;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        neg[index] = negative;                        // written once, into our own slot
        pos[index] = positive;
        zero[index] = zeros;
    }

    // peak memory
    private static void resetPeakMemory() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        }
    }

    private static long peakHeapBytes() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) sum += pool.getPeakUsage().getUsed();
        }
        return sum;
    }
}