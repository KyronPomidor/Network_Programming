package com.pbl;

import java.io.File;
import java.nio.file.Files;

// Task 2: reads every file from a folder and counts negative/positive/zero numbers
// with 1, 2 and 4 threads, then prints the results so they can be compared.
public class Task2 {
    static final String DIR = "parts"; // folder with the files (the only setting)

    static volatile long peak; // peak heap used during a run, in bytes

    static int[] numberOfCheckedThreads = new int[] {1, 2, 4};

    public static void main(String[] args) throws Exception {
        File[] files = new File(DIR).listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            System.out.println("No .txt files found in " + new File(DIR).getAbsolutePath());
            return;
        }
        System.out.println(files.length + " files in " + new File(DIR).getAbsolutePath());

        startMemorySampler();

        System.out.printf("%-8s %-10s %-12s %-12s %-10s %-12s %-10s%n",
        "threads", "time_ms", "negative", "positive", "zero", "total", "peak_MB");
        
        for (int threads : numberOfCheckedThreads) {
            long[][] counts = new long[threads][3]; // per thread: negative, positive, zero
            Thread[] workers = new Thread[threads];
            peak = 0;

            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int id = t;
                workers[t] = new Thread(() -> {
                    // files are shared out evenly: thread id takes files id, id+threads, ...
                    for (int i = id; i < files.length; i += threads) {
                        countFile(files[i], counts[id]);
                    }
                });
                workers[t].start();
            }
            for (Thread w : workers) w.join();
            double seconds = (System.nanoTime() - start) / 1e9;

            // add the per-thread counters together
            long neg = 0, pos = 0, zero = 0;
            for (long[] c : counts) {
                neg += c[0];
                pos += c[1];
                zero += c[2];
            }
            System.out.printf("%-8d %-10.1f %-12d %-12d %-10d %-12d %-10.1f%n",
                threads, seconds * 1000, neg, pos, zero, neg + pos + zero, peak / (1024.0 * 1024.0));
        }
    }

    // reads the whole file in one operation, then parses and counts the numbers
    static void countFile(File file, long[] c) {
        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            throw new RuntimeException("Cannot read " + file, e);
        }

        int value = 0;
        boolean minus = false, inNumber = false;
        for (byte b : data) {
            if (b == '-') {
                minus = true;
            } else if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
                inNumber = true;
            } else if (inNumber) { // any other character ends the number
                add(c, minus, value);
                value = 0;
                minus = false;
                inNumber = false;
            }
        }
        if (inNumber) add(c, minus, value); // last line without a newline
    }

    static void add(long[] c, boolean minus, int value) {
        if (value == 0) c[2]++;
        else if (minus) c[0]++;
        else c[1]++;
    }

    // background thread that records the highest heap usage
    static void startMemorySampler() {
        Thread sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (true) {
                peak = Math.max(peak, rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
    }
}