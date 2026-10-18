package com.pbl;

import java.io.*;
import java.nio.file.*;
import java.util.Random;

// Creates numbers.txt and splits it, in order, into 8 files of 6 250 000 numbers.
public class Generate {
    static final long COUNT = 50_000_000;
    static final int PARTS = 8;
    static final String FILE = "numbers.txt"; // full input file
    static final String DIR = "parts";        // folder for the 8 parts

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(DIR));
        Random rnd = new Random();
        long perPart = COUNT / PARTS;

        BufferedWriter all = new BufferedWriter(new FileWriter(FILE), 1 << 20);
        BufferedWriter part = null;
        for (long i = 0; i < COUNT; i++) {
            if (i % perPart == 0) { // start the next part file
                if (part != null) part.close();
                part = new BufferedWriter(new FileWriter(DIR + "/part_" + (i / perPart) + ".txt"), 1 << 20);
            }
            String line = (rnd.nextInt(2_000_001) - 1_000_000) + "\n"; // -1 000 000 .. 1 000 000
            all.write(line);  // same line goes to numbers.txt ...
            part.write(line); // ... and to its part, so order is kept
        }
        all.close();
        part.close();
        System.out.println("Done: " + FILE + " and " + PARTS + " files in " + DIR);
    }
}