package com.org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

public class Client {
    public static void main(String[] args) {
        // ===== PART 3 START: optional third argument "provider" starts the program as the provider =====
        boolean provider = args.length == 3 && args[2].equalsIgnoreCase("provider");
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !provider)) {
            System.out.println("Usage: java Client <address> <port> [provider]");
            return;
        }
        // ===== PART 3 END =====

        try (Socket server = new Socket(args[0], Integer.parseInt(args[1]));
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(
                     new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8), true)) {
            System.out.println(provider ? "Connected to the coffee shop as the provider."
                    : "Connected to the coffee shop.");

            Semaphore replyReceived = new Semaphore(0);
            Thread keyboard = new Thread(() -> readCommands(server, output, replyReceived, provider));
            keyboard.setDaemon(true);
            keyboard.start();

            // PART 4: this thread shows every message as soon as it arrives, even while the user types
            String reply;
            while ((reply = input.readLine()) != null) {
                showReply(reply);
                // Messages that are not the answer to a command do not release the keyboard thread.
                if (!reply.endsWith(" is ready") && !reply.startsWith("Alert:")) {   // PART 3: alerts
                    replyReceived.release();
                }
            }
            System.out.println("The server closed the connection.");   // PART 4
        } catch (IOException e) {
            System.out.println("Connection ended: " + e.getMessage());
        }
    }

    private static void readCommands(Socket server, PrintWriter output, Semaphore replyReceived,
                                     boolean provider) {
        String action = provider ? "deliver" : "order";   // PART 3
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        try {
            // ===== PART 3 START: tell the server that we are the provider =====
            if (provider) {
                output.println("provider");
                replyReceived.acquire();
            }
            // ===== PART 3 END =====

            while (true) {
                System.out.println("\nChoose: " + action + ", stock, quit");
                String choice = keyboard.readLine();
                if (choice == null || choice.trim().equalsIgnoreCase("quit")) {
                    break;
                }

                if (choice.trim().equalsIgnoreCase("stock")) {
                    output.println("menu");
                } else if (choice.trim().equalsIgnoreCase(action)) {
                    String order = makeOrder(keyboard, action);
                    if (order == null) {
                        break;
                    }
                    if (!order.isEmpty()) {
                        output.println(order);
                    } else {
                        continue;
                    }
                } else {
                    System.out.println("Enter " + action + ", stock, or quit.");
                    continue;
                }

                if (output.checkError()) {
                    System.out.println("Could not send command");
                    break;
                }
                replyReceived.acquire();
            }
            server.shutdownOutput();
        } catch (IOException e) {
            System.out.println("Input ended: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // action is "order" or "deliver": it is the first word of the line sent to the server
    private static String makeOrder(BufferedReader keyboard, String action) throws IOException {
        String[] products = {"coffee", "tea", "croissant"};
        StringBuilder order = new StringBuilder();

        while (true) {
            System.out.println("\nMake " + action + ":");
            System.out.println("1. Coffee");
            System.out.println("2. Tea");
            System.out.println("3. Croissant");
            System.out.println("4. Stop");

            String choice = keyboard.readLine();
            if (choice == null) {
                return null;
            }

            int number = positiveNumber(choice);
            if (number == 4) {
                return order.toString();
            }
            if (number < 1 || number > products.length) {
                System.out.println("Choose a number from 1 to 4.");
                continue;
            }

            System.out.println("Select quantity:");
            String amount = keyboard.readLine();
            if (amount == null) {
                return null;
            }

            int quantity = positiveNumber(amount);
            if (quantity == 0) {
                System.out.println("Enter a positive whole number.");
                continue;
            }

            if (order.length() == 0) {
                order.append(action);
            }
            order.append(" ").append(products[number - 1]).append(" ").append(quantity);
        }
    }

    private static void showReply(String reply) {
        if (reply.startsWith("Menu: ")) {
            StringBuilder stock = new StringBuilder("\nStock:");
            String[] products = reply.substring(6).split(" ");
            for (String product : products) {
                stock.append(System.lineSeparator()).append(product.replace("=", " - "));
            }
            System.out.println(stock);
        } else {
            System.out.println(reply);
        }
    }

    private static int positiveNumber(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}