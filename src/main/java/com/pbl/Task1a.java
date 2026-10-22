package com.pbl;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

public class Task1a {

    private static final String FILE = "numbers.txt";
    private static final long EXPECTED_TOTAL = 50_000_000L;
    private static final int BUFFER_SIZE = 1 << 20; // 1 MiB per thread

    public static void main(String[] args) throws Exception {
        run();
    }

    private static void run() throws Exception {
        RandomAccessFile probe = new RandomAccessFile(FILE, "r");
        long size = probe.length();
        probe.close();
        System.out.printf("%-8s %-10s %-12s %-12s %-10s %-12s %-10s %-6s%n",
                "threads", "time_ms", "negative", "positive", "zero", "total", "peak_MB", "Correctness");

        long[] first = null; // result of the first run, all others must match it
        for (int n = 1; n <= 64; n *= 2) {
            System.gc();
            resetPeakMemory();

            long t0 = System.nanoTime();
            long[] r = count(size, n);
            long t1 = System.nanoTime();

            double ms = (t1 - t0) / 1_000_000.0;
            double peakMb = peakHeapBytes() / (1024.0 * 1024.0);
            long total = r[0] + r[1] + r[2];

            if (first == null) {
                first = r;
            }

            boolean same = r[0] == first[0] && r[1] == first[1] && r[2] == first[2];
            boolean ok = same && total == EXPECTED_TOTAL;

            System.out.printf("%-8d %-10.1f %-12d %-12d %-10d %-12d %-10.1f %-6s%n",
                    n, ms, r[0], r[1], r[2], total, peakMb, ok ? "OK" : "WRONG");
        }
    }

    // Starts n threads, waits for all of them, returns : negative, positive, zero
    private static long[] count(long size, int n) throws InterruptedException {
        // One slot per thread: nobody writes where anybody else writes
        long[] neg = new long[n];
        long[] pos = new long[n];
        long[] zero = new long[n];
        Thread[] threads = new Thread[n];

        for (int i = 0; i < n; i++) {
            final int index = i;
            final long start = size * i / n;
            final long end = size * (i + 1) / n;
            threads[i] = new Thread(() -> {
                try {
                    countPart(start, end, neg, pos, zero, index);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        long sn = 0, sp = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            sn += neg[i];
            sp += pos[i];
            sz += zero[i];
        }
        return new long[]{sn, sp, sz};
    }

    // Reads and parses only the lines that start inside [start, end)
    private static void countPart(long start, long end,
                                  long[] neg, long[] pos, long[] zero, int index) throws IOException {
        if (start >= end) return;

        long negative = 0;
        long positive = 0;
        long zeros = 0;

        RandomAccessFile file = new RandomAccessFile(FILE, "r");
        try {
            byte[] buf = new byte[BUFFER_SIZE];

            // Start one byte early to see whether 'start' is the beginning of a line.
            long p = start > 0 ? start - 1 : 0;
            boolean skipping = start > 0;
            file.seek(p);

            boolean isNegative = false;
            boolean hasDigits = false;
            long value = 0;
            boolean done = false;

            while (!done) {
                int read = file.read(buf);
                if (read < 0) break;

                for (int k = 0; k < read; k++) {
                    byte b = buf[k];

                    if (skipping) {
                        if (b == '\n') {
                            skipping = false;
                            if (p + 1 >= end) {
                                done = true;
                                break;
                            }
                        }
                        p++;
                        continue;
                    }

                    if (b == '\n') {
                        if (hasDigits) {
                            if (value == 0) zeros++;
                            else if (isNegative) negative++;
                            else positive++;
                        }
                        isNegative = false;
                        hasDigits = false;
                        value = 0;
                        if (p + 1 >= end) {
                            done = true;
                            break;
                        } // next line belongs to the next thread
                    }
                    else if (b == '-') {
                        isNegative = true;
                    }
                    else if (b >= '0' && b <= '9') {
                        hasDigits = true;
                        value = value * 10 + (b - '0');
                    }
                    p++;
                }
            }

            if (!done && !skipping && hasDigits) {
                if (value == 0) {
                    zeros++;
                }

                else if (isNegative) {
                    negative++;
                }

                else {
                    positive++;
                }
            }
        } finally {
            file.close();
        }

        neg[index] = negative;
        pos[index] = positive;
        zero[index] = zeros;
    }

    private static void resetPeakMemory() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long peakHeapBytes() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum;
    }
}