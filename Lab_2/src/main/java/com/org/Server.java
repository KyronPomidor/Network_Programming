package com.org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Server {
    private final String[] products = {"coffee", "tea", "croissant"};
    private final int[] times = {4, 3, 2};
    private final int[] stock = new int[products.length];
    private final int workerCount = Runtime.getRuntime().availableProcessors();
    private final ExecutorService workers = Executors.newFixedThreadPool(workerCount);
    private int nextOrder = 1;

    // ===== PART 3 START: provider state (guarded by "this") =====
    private static final int LOW_STOCK = 5;
    private PrintWriter provider;                                  // connection of the provider, null if none
    private final boolean[] alerted = new boolean[products.length]; // alert already sent for this product
    // ===== PART 3 END =====

    // ===== PART 4 START: limits and orders that can be cancelled =====
    private static final int MAX_LINE = 200;

    private static class Order {
        final int number;
        final long[] quantities;
        Future<?> task;          // lets us remove it from the queue or interrupt the worker
        boolean cancelled;       // guarded by the server lock
        boolean ready;           // guarded by the server lock

        Order(int number, long[] quantities) {
            this.number = number;
            this.quantities = quantities;
        }
    }
    // ===== PART 4 END =====

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

        List<Order> orders = new ArrayList<>();   // PART 4: orders of this client
        PrintWriter output = null;

        try (client;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            client.setKeepAlive(true);            // PART 4: helps to notice a dead network
            output = new PrintWriter(
                    new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);

            String line;
            while ((line = readLine(input)) != null) {   // PART 4: bounded readLine
                String reply;

                // A worker must wait until the acceptance message has been sent.
                synchronized (output) {
                    if (line.length() > MAX_LINE) {                       // PART 4
                        reply = "Error: Message too long";
                    } else {
                        String[] words = line.trim().split("\\s+");
                        if (words[0].equalsIgnoreCase("menu") && words.length == 1) {
                            reply = menu();
                        } else if (words[0].equalsIgnoreCase("order")) {
                            reply = order(words, output, endpoint, orders);
                            // ===== PART 3 START: provider commands =====
                        } else if (words[0].equalsIgnoreCase("provider") && words.length == 1) {
                            reply = becomeProvider(output);
                        } else if (words[0].equalsIgnoreCase("deliver")) {
                            reply = deliver(words, output);
                            // ===== PART 3 END =====
                        } else {
                            reply = "Error: Use menu or order <product> <quantity> ...";
                        }
                    }

                    if (!reply.startsWith("Menu:")) {
                        System.out.println(endpoint + " " + reply);
                    }
                    send(output, reply);
                }

                sendAlerts();   // PART 3: the stock may have changed
            }
        } catch (IOException e) {
            System.out.println(endpoint + " Error: " + e.getMessage());
        } finally {
            disconnect(output, orders, endpoint);   // PART 4
        }
        System.out.println(endpoint + " Disconnected");
    }

    // ===== PART 4 START: a line of at most MAX_LINE characters =====
    // Longer lines are read to the end but not stored: the result is longer than MAX_LINE.
    private String readLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (line.length() <= MAX_LINE) {
                line.append((char) c);
            }
        }
        return line.length() > 0 ? line.toString() : null;
    }
    // ===== PART 4 END =====

    private synchronized String menu() {
        String result = "Menu:";
        for (int i = 0; i < products.length; i++) {
            result += " " + products[i] + "=" + stock[i];
        }
        return result;
    }

    private String order(String[] words, PrintWriter output, String endpoint, List<Order> orders) {
        try {
            return acceptOrder(readQuantities(words), output, endpoint, orders);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    // Used by order and deliver: "<command> <product> <quantity> ..."
    private long[] readQuantities(String[] words) {
        if (words.length < 3 || words.length % 2 == 0) {
            throw new IllegalArgumentException("Use " + words[0].toLowerCase() + " <product> <quantity> ...");
        }

        long[] quantities = new long[products.length];

        for (int i = 1; i < words.length; i += 2) {
            int product = findProduct(words[i]);
            if (product == -1) {
                throw new IllegalArgumentException("Unknown product " + words[i]);
            }

            int quantity;
            try {
                quantity = Integer.parseInt(words[i + 1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Quantity must be a positive integer");
            }

            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be a positive integer");
            }
            quantities[product] += quantity;
        }
        return quantities;
    }

    private int findProduct(String name) {
        for (int i = 0; i < products.length; i++) {
            if (products[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private synchronized String acceptOrder(long[] quantities, PrintWriter output, String endpoint,
                                            List<Order> orders) {
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
        Order order = new Order(number, quantities);                       // PART 4
        order.task = workers.submit(() -> prepare(order, preparationTime, output, endpoint));
        orders.add(order);                                                 // PART 4
        return "Order " + number + " accepted. Preparation time: " + seconds + " s";
    }

    private void prepare(Order order, int seconds, PrintWriter output, String endpoint) {
        try {
            Thread.sleep(seconds * 1000L);

            // PART 4 START: if the client left meanwhile, the order is cancelled and nothing is sent
            synchronized (this) {
                if (order.cancelled) {
                    return;
                }
                order.ready = true;
            }
            // PART 4 END

            synchronized (output) {
                String reply = "Order " + order.number + " is ready";
                System.out.println(endpoint + " " + reply);
                send(output, reply);
            }
        } catch (InterruptedException e) {
            // PART 4: the order was cancelled while preparing, the worker stops.
            // cancelOrders has already put the products back.
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

    // ===== PART 3 START: the provider =====
    private synchronized String becomeProvider(PrintWriter output) {
        if (provider != null && provider != output) {
            return "Error: A provider is already connected";
        }
        provider = output;
        return "You are the provider";
    }

    private synchronized String deliver(String[] words, PrintWriter output) {
        if (output != provider) {
            return "Error: Only the provider can deliver";
        }

        long[] quantities;
        try {
            quantities = readQuantities(words);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        for (int i = 0; i < stock.length; i++) {
            if (stock[i] + quantities[i] > Integer.MAX_VALUE) {
                return "Error: Too many " + products[i];
            }
        }
        for (int i = 0; i < stock.length; i++) {
            stock[i] += (int) quantities[i];
        }
        return "Delivery accepted";
    }

    // Called by the thread that has just changed the stock (a client thread), after its own reply.
    // A product is announced once when it falls below LOW_STOCK, and again after it has recovered.
    // Without a provider nothing is marked, so a provider that connects later gets the alerts.
    private void sendAlerts() {
        PrintWriter target;
        List<String> alerts = new ArrayList<>();

        synchronized (this) {
            target = provider;
            if (target == null) {
                return;
            }
            for (int i = 0; i < stock.length; i++) {
                if (stock[i] >= LOW_STOCK) {
                    alerted[i] = false;
                } else if (!alerted[i]) {
                    alerted[i] = true;
                    alerts.add("Alert: " + products[i] + " is low: " + stock[i] + " left");
                }
            }
        }

        // The lock of the server is released: no thread holds it while waiting for a connection.
        for (String alert : alerts) {
            synchronized (target) {
                try {
                    System.out.println("Provider " + alert);
                    send(target, alert);
                } catch (IOException e) {
                    System.out.println("Provider Error: " + e.getMessage());
                }
            }
        }
    }
    // ===== PART 3 END =====

    // ===== PART 4 START: a connection has ended =====
    private void disconnect(PrintWriter output, List<Order> orders, String endpoint) {
        synchronized (this) {
            if (output != null && output == provider) {
                provider = null;
                Arrays.fill(alerted, false);
                System.out.println(endpoint + " Provider left");
            }
        }
        cancelOrders(orders, endpoint);
        sendAlerts();   // the returned products may have changed the stock
    }

    // Orders that are waiting are removed from the queue, the one being prepared is interrupted.
    // Their products go back to the stock. Ready orders are not touched.
    private synchronized void cancelOrders(List<Order> orders, String endpoint) {
        for (Order order : orders) {
            if (!order.ready && !order.cancelled) {
                order.cancelled = true;
                order.task.cancel(true);
                for (int i = 0; i < stock.length; i++) {
                    stock[i] += (int) order.quantities[i];
                }
                System.out.println(endpoint + " Order " + order.number + " cancelled");
            }
        }
    }
    // ===== PART 4 END =====
}