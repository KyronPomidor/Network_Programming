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
        if (args.length != 2) {
            System.out.println("Usage: java Client <address> <port>");
            return;
        }

        try (Socket server = new Socket(args[0], Integer.parseInt(args[1]));
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(
                     new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8), true)) {
            System.out.println("Connected to the coffee shop.");

            Semaphore replyReceived = new Semaphore(0);
            Thread keyboard = new Thread(() -> readCommands(server, output, replyReceived));
            keyboard.setDaemon(true);
            keyboard.start();

            String reply;
            while ((reply = input.readLine()) != null) {
                showReply(reply);
                if (!reply.endsWith(" is ready")) {
                    replyReceived.release();
                }
            }
        } catch (IOException e) {
            System.out.println("Connection ended: " + e.getMessage());
        }
    }

    private static void readCommands(Socket server, PrintWriter output, Semaphore replyReceived) {
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (true) {
                System.out.println("\nChoose: order, stock, quit");
                String choice = keyboard.readLine();
                if (choice == null || choice.trim().equalsIgnoreCase("quit")) {
                    break;
                }

                if (choice.trim().equalsIgnoreCase("stock")) {
                    output.println("menu");
                } else if (choice.trim().equalsIgnoreCase("order")) {
                    String order = makeOrder(keyboard);
                    if (order == null) {
                        break;
                    }
                    if (!order.isEmpty()) {
                        output.println(order);
                    } else {
                        continue;
                    }
                } else {
                    System.out.println("Enter order, stock, or quit.");
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

    private static String makeOrder(BufferedReader keyboard) throws IOException {
        String[] products = {"coffee", "tea", "croissant"};
        StringBuilder order = new StringBuilder();

        while (true) {
            System.out.println("\nMake order:");
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
                order.append("order");
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


