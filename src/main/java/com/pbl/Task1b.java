package com.pbl;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class Task1b {

    private static final String FILE = "numbers.txt";
    private static final long EXPECTED_TOTAL = 50_000_000L;
    private static final int BUFFER_SIZE = 1 << 20;

    public static void main(String[] args) throws Exception {
        run();
    }

    // version 1: shared counters, NO synchronisation
    private static long sharedNeg, sharedPos, sharedZero;

    // version 2: the same counters, protected by a lock
    private static final Object gate = new Object();

    private static void run() throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();

        // not measured
        int[] numbers = loadNumbers();
        int length = loadedLength;
        System.out.println("numbers loaded: " + length + ", threads: " + threads);
        System.out.println();
        System.out.printf("%-26s %-10s %-12s %-12s %-10s %-12s %-8s%n",
                "version", "time_ms", "negative", "positive", "zero", "total", "Correctness");

        // reference answer (own counters), computed first; this run is not printed
        // and also warms up the JIT, so the printed runs start from the same state
        long[] ref = runVersion(3, numbers, length, threads);

        for (int run = 1; run <= 3; run++)
            printRow("1 no sync, run " + run, runVersion(1, numbers, length, threads), ref);
        printRow("2 lock", runVersion(2, numbers, length, threads), ref);
        printRow("3 own counters", runVersion(3, numbers, length, threads), ref);
    }

    private static void printRow(String name, long[] r, long[] ref) {
        long total = r[0] + r[1] + r[2];
        boolean ok = r[0] == ref[0] && r[1] == ref[1] && r[2] == ref[2] && total == EXPECTED_TOTAL;
        System.out.printf("%-26s %-10d %-12d %-12d %-10d %-12d %-8s%n",
                name, r[3], r[0], r[1], r[2], total, ok ? "OK" : "WRONG");
    }

    // Returns : negative, positive, zero, time_ms
    private static long[] runVersion(int version, int[] numbers, int length, int n) throws InterruptedException {
        sharedNeg = 0;
        sharedPos = 0;
        sharedZero = 0;

        long[] neg = new long[n]; // used by version 3: one slot per thread
        long[] pos = new long[n];
        long[] zero = new long[n];
        Thread[] threads = new Thread[n];

        long t0 = System.nanoTime();

        for (int i = 0; i < n; i++) {
            final int index = i;
            final int from = (int) ((long) length * i / n);
            final int to = (int) ((long) length * (i + 1) / n);

            if (version == 1) {
                threads[i] = new Thread(() -> countShared(numbers, from, to));
            } else if (version == 2) {
                threads[i] = new Thread(() -> countWithLock(numbers, from, to));
            } else {
                threads[i] = new Thread(() -> {
                    long a = 0, b = 0, c = 0;       // local variables: nobody else sees them
                    for (int k = from; k < to; k++) {
                        int v = numbers[k];
                        if (v < 0) a++;
                        else if (v > 0) b++;
                        else c++;
                    }
                    neg[index] = a;                 // written once, into our own slot
                    pos[index] = b;
                    zero[index] = c;
                });
            }
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (version == 3) {
            long sn = 0, sp = 0, sz = 0;
            for (int i = 0; i < n; i++) {
                sn += neg[i];
                sp += pos[i];
                sz += zero[i];
            }
            return new long[]{sn, sp, sz, ms};
        }
        return new long[]{sharedNeg, sharedPos, sharedZero, ms};
    }

    // version 1: counter++ is read, add, write - updates get lost
    private static void countShared(int[] numbers, int from, int to) {
        for (int k = from; k < to; k++) {
            int v = numbers[k];
            if (v < 0) sharedNeg++;
            else if (v > 0) sharedPos++;
            else sharedZero++;
        }
    }

    // version 2: correct, but every number waits for the lock
    private static void countWithLock(int[] numbers, int from, int to) {
        for (int k = from; k < to; k++) {
            int v = numbers[k];
            synchronized (gate) {
                if (v < 0) sharedNeg++;
                else if (v > 0) sharedPos++;
                else sharedZero++;
            }
        }
    }

    // loading (one thread, not measured) 
    private static int loadedLength;

    // Reads numbers.txt on one thread into an int[]. The real count is in loadedLength
    private static int[] loadNumbers() throws IOException {
        int[] a = new int[1 << 20];
        int count = 0;

        RandomAccessFile file = new RandomAccessFile(FILE, "r");
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            boolean isNegative = false;
            boolean hasDigits = false;
            int value = 0;

            int read;
            while ((read = file.read(buf)) > 0) {
                for (int k = 0; k < read; k++) {
                    byte b = buf[k];
                    if (b == '\n') {
                        if (hasDigits) {
                            if (count == a.length) a = Arrays.copyOf(a, a.length * 2);
                            a[count++] = isNegative ? -value : value;
                        }
                        isNegative = false;
                        hasDigits = false;
                        value = 0;
                    } else if (b == '-') {
                        isNegative = true;
                    } else if (b >= '0' && b <= '9') {
                        hasDigits = true;
                        value = value * 10 + (b - '0');
                    }
                }
            }
            if (hasDigits) { // last line without '\n'
                if (count == a.length) a = Arrays.copyOf(a, a.length * 2);
                a[count++] = isNegative ? -value : value;
            }
        } finally {
            file.close();
        }

        loadedLength = count;
        return a;
    }
}