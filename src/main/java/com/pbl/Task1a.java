package com.pbl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

public class Task1a {

    private static final String FILE = "numbers.txt";
    private static final long EXPECTED_TOTAL = 50_000_000L;

    public static void main(String[] args) throws Exception {
        long size = new File(FILE).length();

        System.out.printf("%-8s %-10s %-12s %-12s %-10s %-12s %-10s %s%n",
                "threads", "time_ms", "negative", "positive", "zero", "total", "peak_MB", "Correctness");

        long[] first = null; // result of the first run: all other runs must give the same

        for (int n = 1; n <= 64; n *= 2) {   // 1, 2, 4, ... 64 threads
            System.gc();
            resetPeakMemory();

            long t0 = System.nanoTime();
            long[] r = count(size, n);       // r = {negative, positive, zero}
            long t1 = System.nanoTime();

            double ms = (t1 - t0) / 1_000_000.0;
            double peakMb = peakHeapBytes() / (1024.0 * 1024.0);
            long total = r[0] + r[1] + r[2];

            if (first == null) first = r;
            boolean same = r[0] == first[0] && r[1] == first[1] && r[2] == first[2];
            boolean ok = same && total == EXPECTED_TOTAL;

            System.out.printf("%-8d %-10.1f %-12d %-12d %-10d %-12d %-10.1f %s%n",
                    n, ms, r[0], r[1], r[2], total, peakMb, ok ? "OK" : "WRONG");
        }
    }

    // Starts n threads (one part of the file each), waits for all of them
    private static long[] count(long size, int n) throws InterruptedException {
        // Step 1 of the ladder - don't share: one slot per thread
        long[] neg = new long[n];
        long[] pos = new long[n];
        long[] zero = new long[n];
        Thread[] threads = new Thread[n];

        for (int i = 0; i < n; i++) {
            int index = i;                    // a copy, as always
            long start = size * i / n;
            long end = size * (i + 1) / n;

            threads[i] = new Thread(() -> {
                try {
                    countPart(start, end, neg, pos, zero, index);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        for (Thread t : threads)
            t.join();

        // safe to add up now: every thread has finished writing
        long sn = 0, sp = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            sn += neg[i];
            sp += pos[i];
            sz += zero[i];
        }
        return new long[]{sn, sp, sz};
    }

    // A thread owns every line that STARTS inside [start, end).
    private static void countPart(long start, long end,
                                  long[] neg, long[] pos, long[] zero, int index) throws IOException {
        long negative = 0, positive = 0, zeros = 0;   // local counters

        try (FileInputStream in = new FileInputStream(FILE)) {
            // p = position (in bytes) where the next line starts.
            // Begin one byte early, at start - 1, to see where the line borders are.
            long p = start > 0 ? start - 1 : 0;
            in.getChannel().position(p);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            if (start > 0) {
                // Skip the rest of the line that belongs to the previous thread.
                // If byte start - 1 is '\n', this line is empty and nothing is lost.
                String tail = reader.readLine();
                p += tail.length() + 1;           // +1 for '\n' (the file has 1-byte line ends)
            }

            String line;
            while (p < end && (line = reader.readLine()) != null) {
                p += line.length() + 1;

                int value = Integer.parseInt(line.trim());
                if (value < 0) negative++;
                else if (value > 0) positive++;
                else zeros++;
            }
        }

        neg[index] = negative;
        pos[index] = positive;
        zero[index] = zeros;
    }

    // ---- peak memory: the highest usage of the heap since the last reset ----
    private static void resetPeakMemory() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getType() == MemoryType.HEAP)
                pool.resetPeakUsage();
    }

    private static long peakHeapBytes() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getType() == MemoryType.HEAP)
                sum += pool.getPeakUsage().getUsed();
        return sum;
    }
}