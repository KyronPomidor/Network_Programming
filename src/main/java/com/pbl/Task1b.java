package com.pbl;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class Task1b {

    private static final String FILE = "numbers.txt";
    private static final long EXPECTED_TOTAL = 50_000_000L;

    // versions 1 and 2: counters shared by all threads
    private static long sharedNeg, sharedPos, sharedZero;

    // version 2: the lock that protects the shared counters
    private static final Object gate = new Object();

    public static void main(String[] args) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();

        int[] numbers = loadNumbers();   // not measured
        System.out.println("numbers loaded: " + numbers.length + ", threads: " + threads);
        System.out.println();
        System.out.printf("%-20s %-10s %-12s %-12s %-10s %-12s %s%n",
                "version", "time_ms", "negative", "positive", "zero", "total", "Correctness");

        // The reference answer (own counters). This run is not printed; it also warms up the JIT.
        long[] ref = countOwnCounters(numbers, threads);

        // version 1, three times: the answer is different every time
        for (int run = 1; run <= 3; run++) {
            long t0 = System.nanoTime();
            long[] r = countNoSync(numbers, threads);
            printRow("1 no sync, run " + run, r, System.nanoTime() - t0, ref);
        }

        long t0 = System.nanoTime();
        long[] r = countWithLock(numbers, threads);
        printRow("2 lock", r, System.nanoTime() - t0, ref);

        t0 = System.nanoTime();
        r = countOwnCounters(numbers, threads);
        printRow("3 own counters", r, System.nanoTime() - t0, ref);
    }

    // ---------------------------------------------------------------------
    // Version 1: shared counters, NO synchronisation.
    // counter++ is read, add, write - so updates get lost.
    // ---------------------------------------------------------------------
    private static long[] countNoSync(int[] numbers, int n) throws InterruptedException {
        sharedNeg = 0;
        sharedPos = 0;
        sharedZero = 0;

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) numbers.length * i / n);
            int to = (int) ((long) numbers.length * (i + 1) / n);

            threads[i] = new Thread(() -> {
                for (int k = from; k < to; k++) {
                    int v = numbers[k];
                    if (v < 0) sharedNeg++;
                    else if (v > 0) sharedPos++;
                    else sharedZero++;
                }
            });
            threads[i].start();
        }

        for (Thread t : threads)
            t.join();

        return new long[]{sharedNeg, sharedPos, sharedZero};
    }

    // ---------------------------------------------------------------------
    // Version 2: the same counters, but every increment is inside a lock.
    // Correct, but every number waits for the lock.
    // ---------------------------------------------------------------------
    private static long[] countWithLock(int[] numbers, int n) throws InterruptedException {
        sharedNeg = 0;
        sharedPos = 0;
        sharedZero = 0;

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) numbers.length * i / n);
            int to = (int) ((long) numbers.length * (i + 1) / n);

            threads[i] = new Thread(() -> {
                for (int k = from; k < to; k++) {
                    int v = numbers[k];
                    synchronized (gate) {
                        if (v < 0) sharedNeg++;
                        else if (v > 0) sharedPos++;
                        else sharedZero++;
                    }
                }
            });
            threads[i].start();
        }

        for (Thread t : threads)
            t.join();

        return new long[]{sharedNeg, sharedPos, sharedZero};
    }

    // ---------------------------------------------------------------------
    // Version 3: don't share. Every thread counts in its own variables
    // and writes once into its own slot.
    // ---------------------------------------------------------------------
    private static long[] countOwnCounters(int[] numbers, int n) throws InterruptedException {
        long[] neg = new long[n];
        long[] pos = new long[n];
        long[] zero = new long[n];

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            int index = i;                 // a copy, as always
            int from = (int) ((long) numbers.length * i / n);
            int to = (int) ((long) numbers.length * (i + 1) / n);

            threads[i] = new Thread(() -> {
                long negative = 0, positive = 0, zeros = 0;   // nobody else sees them
                for (int k = from; k < to; k++) {
                    int v = numbers[k];
                    if (v < 0) negative++;
                    else if (v > 0) positive++;
                    else zeros++;
                }
                neg[index] = negative;
                pos[index] = positive;
                zero[index] = zeros;
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

    // Reads numbers.txt on ONE thread into an int[] (this is not measured).
    private static int[] loadNumbers() throws IOException {
        int[] numbers = new int[(int) EXPECTED_TOTAL];
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                numbers[count++] = Integer.parseInt(line);
            }
        }

        if (count != numbers.length)
            throw new IllegalStateException("expected " + numbers.length + " numbers, found " + count);
        return numbers;
    }

    private static void printRow(String name, long[] r, long nanos, long[] ref) {
        long total = r[0] + r[1] + r[2];
        boolean ok = r[0] == ref[0] && r[1] == ref[1] && r[2] == ref[2] && total == EXPECTED_TOTAL;

        System.out.printf("%-20s %-10d %-12d %-12d %-10d %-12d %s%n",
                name, nanos / 1_000_000, r[0], r[1], r[2], total, ok ? "OK" : "WRONG");
    }
}