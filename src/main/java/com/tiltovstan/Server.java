package com.tiltovstan;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private static final int PORT = 5050;
    private static final String USER_FILE = "users.txt";
    private static final String ROOM_FILE = "rooms.txt";

    // Active users: session -> username
    private static final Map<ClientHandler, String> activeUsers = new ConcurrentHashMap<>();
    // Rooms: room -> set of clients
    private static final Map<String, Set<ClientHandler>> rooms = new ConcurrentHashMap<>();
    // Room message history (last N messages)
    private static final Map<String, Deque<String>> roomHistory = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    // DM history: (user1:user2) -> messages
    private static final Map<String, Deque<String>> dmHistory = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Chat server started on port " + PORT);
        loadRooms();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ----- Load rooms -----
    private static void loadRooms() {
        try {
            File file = new File(ROOM_FILE);
            if (!file.exists()) {
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("general\n");
                }
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String room = line.trim();
                    rooms.putIfAbsent(room, ConcurrentHashMap.newKeySet());
                    roomHistory.putIfAbsent(room, new ArrayDeque<>());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ----- Create room -----
    private static synchronized boolean createRoom(String room) {
        try {
            File file = new File(ROOM_FILE);
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase(room)) return false;
                }
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(room + "\n");
            }
            rooms.putIfAbsent(room, ConcurrentHashMap.newKeySet());
            roomHistory.putIfAbsent(room, new ArrayDeque<>());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ----- Get key for DM history -----
    private static String getDmKey(String user1, String user2) {
        List<String> pair = Arrays.asList(user1, user2);
        Collections.sort(pair);
        return pair.get(0) + ":" + pair.get(1);
    }

    // ================= CLIENT HANDLER =================
    static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username = null;
        private String currentRoom = null;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    handleMessage(line.trim());
                }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + username);
            } finally {
                cleanup();
            }
        }

        private void handleMessage(String json) {
            // --- Register ---
            if (json.contains("\"type\":\"register\"")) {
                String user = extractValue(json, "username");
                String pass = extractValue(json, "password");
                if (registerUser(user, pass)) {
                    sendJson("{\"type\":\"success\",\"message\":\"Registration successful\"}");
                } else {
                    sendJson("{\"type\":\"error\",\"message\":\"User already exists\"}");
                }
            }

            // --- Login ---
            else if (json.contains("\"type\":\"login\"")) {
                String user = extractValue(json, "username");
                String pass = extractValue(json, "password");
                if (validateUser(user, pass)) {
                    this.username = user;
                    activeUsers.put(this, user);
                    sendJson("{\"type\":\"success\",\"message\":\"Login successful\"}");
                } else {
                    sendJson("{\"type\":\"error\",\"message\":\"Invalid username/password\"}");
                }
            }

            // --- Logout ---
            else if (json.contains("\"type\":\"logout\"")) {
                sendJson("{\"type\":\"success\",\"message\":\"Logged out\"}");
                cleanup();
            }

            // --- Create Room ---
            else if (json.contains("\"type\":\"create_room\"")) {
                String room = extractValue(json, "room");
                if (createRoom(room)) {
                    sendJson("{\"type\":\"success\",\"message\":\"Room created\"}");
                } else {
                    sendJson("{\"type\":\"error\",\"message\":\"Room already exists\"}");
                }
            }

            // --- List Rooms ---
            else if (json.contains("\"type\":\"list_rooms\"")) {
                sendRoomList();
            }

            // --- Join Room ---
            else if (json.contains("\"type\":\"join\"")) {
                String room = extractValue(json, "room");
                rooms.putIfAbsent(room, ConcurrentHashMap.newKeySet());
                roomHistory.putIfAbsent(room, new ArrayDeque<>());
                rooms.get(room).add(this);
                currentRoom = room;

                // Send last messages
                Deque<String> history = roomHistory.get(room);
                if (!history.isEmpty()) {
                    StringBuilder sb = new StringBuilder("{\"type\":\"history\",\"room\":\"" + room + "\",\"messages\":[");
                    sb.append(String.join(",", history));
                    sb.append("]}");
                    sendJson(sb.toString());
                }

                sendJson("{\"type\":\"success\",\"message\":\"Joined room " + room + "\"}");
            }

            // --- Leave Room ---
            else if (json.contains("\"type\":\"leave\"")) {
                if (currentRoom != null) {
                    rooms.get(currentRoom).remove(this);
                    sendJson("{\"type\":\"success\",\"message\":\"Left room " + currentRoom + "\"}");
                    currentRoom = null;
                }
            }

            // --- Room Users ---
            else if (json.contains("\"type\":\"room_users\"")) {
                String room = extractValue(json, "room");
                if (rooms.containsKey(room)) {
                    String users = rooms.get(room).stream()
                            .map(c -> c.username)
                            .filter(Objects::nonNull)
                            .reduce((a, b) -> a + "\",\"" + b)
                            .orElse("");
                    sendJson("{\"type\":\"room_users\",\"room\":\"" + room + "\",\"users\":[\"" + users + "\"]}");
                }
            }

            // --- User List ---
            else if (json.contains("\"type\":\"user_list\"")) {
                String users = String.join("\",\"", activeUsers.values());
                sendJson("{\"type\":\"user_list\",\"users\":[\"" + users + "\"]}");
            }

            // --- Direct Message ---
            else if (json.contains("\"type\":\"direct_message\"")) {
                String toUser = extractValue(json, "to");
                String text = extractValue(json, "text");

                for (Map.Entry<ClientHandler, String> entry : activeUsers.entrySet()) {
                    if (entry.getValue().equals(toUser)) {
                        String msg = "{\"type\":\"direct_message\",\"from\":\"" + username + "\",\"text\":\"" + text + "\"}";
                        entry.getKey().sendJson(msg);

                        // Save to DM history
                        String key = getDmKey(username, toUser);
                        dmHistory.putIfAbsent(key, new ArrayDeque<>());
                        Deque<String> history = dmHistory.get(key);
                        if (history.size() >= MAX_HISTORY) history.removeFirst();
                        history.addLast(msg);

                        sendJson("{\"type\":\"success\",\"message\":\"DM sent to " + toUser + "\"}");
                        return;
                    }
                }
                sendJson("{\"type\":\"error\",\"message\":\"User not found\"}");
            }

            // --- DM History ---
            else if (json.contains("\"type\":\"dm_history\"")) {
                String withUser = extractValue(json, "with");
                String key = getDmKey(username, withUser);
                Deque<String> history = dmHistory.getOrDefault(key, new ArrayDeque<>());
                StringBuilder sb = new StringBuilder("{\"type\":\"dm_history\",\"with\":\"" + withUser + "\",\"messages\":[");
                sb.append(String.join(",", history));
                sb.append("]}");
                sendJson(sb.toString());
            }

            // --- Typing Indicator ---
            else if (json.contains("\"type\":\"typing\"")) {
                String room = extractValue(json, "room");
                String msg = "{\"type\":\"typing\",\"room\":\"" + room + "\",\"nick\":\"" + username + "\"}";
                broadcast(room, msg);
            }

            // --- Reaction ---
            else if (json.contains("\"type\":\"reaction\"")) {
                String room = extractValue(json, "room");
                String msgId = extractValue(json, "msg");
                String emoji = extractValue(json, "emoji");
                String msg = "{\"type\":\"reaction\",\"room\":\"" + room + "\",\"msg\":\"" + msgId + "\",\"nick\":\"" + username + "\",\"emoji\":\"" + emoji + "\"}";
                broadcast(room, msg);
            }

            // --- Room Message ---
            else if (json.contains("\"type\":\"message\"")) {
                if (username == null || currentRoom == null) {
                    sendJson("{\"type\":\"error\",\"message\":\"Join a room first\"}");
                    return;
                }
                String text = extractValue(json, "text");
                String msg = "{\"type\":\"message\",\"room\":\"" + currentRoom + "\",\"nick\":\"" + username + "\",\"text\":\"" + text + "\"}";

                // Save to history
                Deque<String> history = roomHistory.get(currentRoom);
                if (history.size() >= MAX_HISTORY) history.removeFirst();
                history.addLast(msg);

                broadcast(currentRoom, msg);
            }

            // --- Unknown ---
            else {
                sendJson("{\"type\":\"error\",\"message\":\"Unknown command\"}");
            }
        }

        // ----- Helpers -----
        private void sendRoomList() {
            String allRooms = String.join("\",\"", rooms.keySet());
            sendJson("{\"type\":\"room_list\",\"rooms\":[\"" + allRooms + "\"]}");
        }

        private void sendJson(String msg) {
            out.println(msg);
        }

        private void broadcast(String room, String msg) {
            if (rooms.containsKey(room)) {
                for (ClientHandler client : rooms.get(room)) {
                    client.sendJson(msg);
                }
            }
        }

        private void cleanup() {
            activeUsers.remove(this);
            if (currentRoom != null) {
                rooms.get(currentRoom).remove(this);
            }
        }

        private synchronized boolean registerUser(String username, String password) {
            try {
                File file = new File(USER_FILE);
                if (!file.exists()) file.createNewFile();
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(username + ":")) return false;
                    }
                }
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(username + ":" + password + "\n");
                }
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        private boolean validateUser(String username, String password) {
            try (BufferedReader reader = new BufferedReader(new FileReader(USER_FILE))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals(username + ":" + password)) return true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return false;
        }

        private String extractValue(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start == -1) return "";
            start += pattern.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        }
    }
}
