package com.org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private final String[] products = {"coffee", "tea", "croissant"};
    private final int[] times = {4, 3, 2};
    private final int[] stock = new int[products.length];
    private final int workerCount = Runtime.getRuntime().availableProcessors();
    private final ExecutorService workers = Executors.newFixedThreadPool(workerCount);
    private int nextOrder = 1;

    public Server() {
        Random random = new Random();
        for (int i = 0; i < stock.length; i++) {
            stock[i] = 10 + random.nextInt(41);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }

        Server shop = new Server();
        shop.start(Integer.parseInt(args[0]));
    }

    private void start(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Listening on port " + port + " with " + workerCount + " workers");
            while (true) {
                Socket client = server.accept();
                new Thread(() -> handle(client)).start();
            }
        } finally {
            workers.shutdown();
        }
    }

    private void handle(Socket client) {
        String endpoint = client.getRemoteSocketAddress().toString();
        System.out.println(endpoint + " Connected");

        try (client;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);

            String line;
            while ((line = input.readLine()) != null) {
                String[] words = line.trim().split("\\s+");
                String reply;

                // A worker must wait until the acceptance message has been sent.
                synchronized (output) {
                    if (words[0].equalsIgnoreCase("menu") && words.length == 1) {
                        reply = menu();
                    } else if (words[0].equalsIgnoreCase("order")) {
                        reply = order(words, output, endpoint);
                    } else {
                        reply = "Error: Use menu or order <product> <quantity> ...";
                    }

                    if (!reply.startsWith("Menu:")) {
                        System.out.println(endpoint + " " + reply);
                    }
                    send(output, reply);
                }
            }
        } catch (IOException e) {
            System.out.println(endpoint + " Error: " + e.getMessage());
        }
        System.out.println(endpoint + " Disconnected");
    }

    private synchronized String menu() {
        String result = "Menu:";
        for (int i = 0; i < products.length; i++) {
            result += " " + products[i] + "=" + stock[i];
        }
        return result;
    }

    private String order(String[] words, PrintWriter output, String endpoint) {
        if (words.length < 3 || words.length % 2 == 0) {
            return "Error: Use order <product> <quantity> ...";
        }

        long[] quantities = new long[products.length];

        for (int i = 1; i < words.length; i += 2) {
            int product = findProduct(words[i]);
            if (product == -1) {
                return "Error: Unknown product " + words[i];
            }

            int quantity;
            try {
                quantity = Integer.parseInt(words[i + 1]);
            } catch (NumberFormatException e) {
                return "Error: Quantity must be a positive integer";
            }

            if (quantity <= 0) {
                return "Error: Quantity must be a positive integer";
            }
            quantities[product] += quantity;
        }

        return acceptOrder(quantities, output, endpoint);
    }

    private int findProduct(String name) {
        for (int i = 0; i < products.length; i++) {
            if (products[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private synchronized String acceptOrder(long[] quantities, PrintWriter output, String endpoint) {
        for (int i = 0; i < stock.length; i++) {
            if (quantities[i] > stock[i]) {
                return "Order refused: " + stock[i] + " " + products[i] + " left";
            }
        }

        int seconds = 0;
        for (int i = 0; i < stock.length; i++) {
            int quantity = (int) quantities[i];
            stock[i] -= quantity;
            seconds += quantity * times[i];
        }

        int number = nextOrder++;
        int preparationTime = seconds;
        workers.execute(() -> prepare(number, preparationTime, output, endpoint));
        return "Order " + number + " accepted. Preparation time: " + seconds + " s";
    }

    private void prepare(int number, int seconds, PrintWriter output, String endpoint) {
        try {
            Thread.sleep(seconds * 1000L);
            synchronized (output) {
                String reply = "Order " + number + " is ready";
                System.out.println(endpoint + " " + reply);
                send(output, reply);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(endpoint + " Error: Preparation interrupted for order " + number);
        } catch (IOException e) {
            System.out.println(endpoint + " Error: " + e.getMessage());
        }
    }

    private void send(PrintWriter output, String reply) throws IOException {
        output.println(reply);
        if (output.checkError()) {
            throw new IOException("Could not send: " + reply);
        }
    }
}
