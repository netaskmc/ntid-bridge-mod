package cc.netask.ntidbridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BridgeClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ntid-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Object outboundLock = new Object();
    private final ArrayDeque<String> outboundQueue = new ArrayDeque<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final StringBuilder incoming = new StringBuilder();

    private volatile MinecraftServer server;
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean running;
    private volatile boolean authenticated;

    public void start(MinecraftServer server) {
        this.server = server;
        stopping.set(false);

        if (!BridgeConfig.ENABLED.get()) {
            running = false;
            clearOutboundQueue();
            NtidBridgeMod.LOGGER.info("NTID bridge is disabled by config");
            return;
        }

        if (BridgeConfig.SERVER_TOKEN.get().isBlank()) {
            running = false;
            clearOutboundQueue();
            NtidBridgeMod.LOGGER.warn("NTID bridge serverToken is empty; not connecting");
            return;
        }

        running = true;
        connect();
    }

    public void stop() {
        stopping.set(true);
        running = false;
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
        }
        WebSocket current = socket;
        socket = null;
        authenticated = false;
        clearOutboundQueue();
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }
    }

    public void sendMinecraftChat(String senderName, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "minecraft.chat");
        payload.addProperty("senderName", senderName);
        payload.addProperty("message", message);
        send(payload);
    }

    public void sendSystem(String kind, String message, ServerPlayer player) {
        if (!BridgeConfig.FORWARD_JOIN_LEAVE.get() && ("join".equals(kind) || "leave".equals(kind))) {
            return;
        }

        JsonObject payload = baseSystemPayload(kind, player);
        payload.addProperty("message", message);
        send(payload);
    }

    public void sendSystem(String kind, Component message, ServerPlayer player) {
        sendSystem(kind, ComponentLocalizer.localize(message, server, BridgeConfig.MESSAGE_LANGUAGE.get()), player);
    }

    public void sendAdvancement(ServerPlayer player, Component title, Component description) {
        JsonObject payload = baseSystemPayload("advancement", player);
        payload.addProperty("title", ComponentLocalizer.localize(title, server, BridgeConfig.MESSAGE_LANGUAGE.get()));
        payload.addProperty("description", ComponentLocalizer.localize(description, server, BridgeConfig.MESSAGE_LANGUAGE.get()));
        send(payload);
    }

    private JsonObject baseSystemPayload(String kind, ServerPlayer player) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "minecraft.system");
        payload.addProperty("kind", kind);
        payload.addProperty("playerName", player.getGameProfile().getName());
        payload.addProperty("playerUuid", player.getUUID().toString());
        return payload;
    }

    private void connect() {
        try {
            URI uri = buildUri();
            NtidBridgeMod.LOGGER.info("Connecting NTID bridge to {}", uri);
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .executor(executor)
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, this)
                    .exceptionally(error -> {
                        NtidBridgeMod.LOGGER.warn("NTID bridge connection failed", error);
                        scheduleReconnect();
                        return null;
                    });
        } catch (RuntimeException error) {
            NtidBridgeMod.LOGGER.warn("Invalid NTID bridge configuration", error);
            scheduleReconnect();
        }
    }

    private URI buildUri() {
        String token = URLEncoder.encode(BridgeConfig.SERVER_TOKEN.get(), StandardCharsets.UTF_8);
        String url = BridgeConfig.BRIDGE_URL.get();
        String separator = url.contains("?") ? "&" : "?";
        return URI.create(url + separator + "serverToken=" + token);
    }

    private void scheduleReconnect() {
        if (stopping.get()) {
            return;
        }

        ScheduledFuture<?> existing = reconnectTask;
        if (existing != null && !existing.isDone()) {
            return;
        }

        reconnectTask = executor.schedule(this::connect, BridgeConfig.RECONNECT_SECONDS.get(), TimeUnit.SECONDS);
    }

    private void send(JsonObject payload) {
        send(GSON.toJson(payload));
    }

    private void send(String payload) {
        if (!running) {
            return;
        }

        WebSocket current = socket;
        if (current == null || !authenticated) {
            enqueue(payload);
            return;
        }

        sendNow(current, payload);
    }

    private boolean sendNow(WebSocket current, String payload) {
        try {
            current.sendText(payload, true).exceptionally(error -> {
                handleSendFailure(current, payload, error);
                return null;
            });
            return true;
        } catch (RuntimeException error) {
            handleSendFailure(current, payload, error);
            return false;
        }
    }

    private void handleSendFailure(WebSocket failedSocket, String payload, Throwable error) {
        if (stopping.get()) {
            return;
        }

        NtidBridgeMod.LOGGER.warn("Failed to send NTID bridge payload; queueing for reconnect", error);
        enqueueFirst(payload);
        if (socket == failedSocket) {
            socket = null;
            authenticated = false;
            scheduleReconnect();
        }
    }

    private void enqueue(String payload) {
        synchronized (outboundLock) {
            int maximumMessages = BridgeConfig.MAX_QUEUED_MESSAGES.get();
            if (maximumMessages <= 0) {
                return;
            }

            while (outboundQueue.size() >= maximumMessages) {
                outboundQueue.removeFirst();
                NtidBridgeMod.LOGGER.warn("NTID bridge outbound queue is full; dropping oldest queued message");
            }
            outboundQueue.addLast(payload);
        }
    }

    private void enqueueFirst(String payload) {
        synchronized (outboundLock) {
            int maximumMessages = BridgeConfig.MAX_QUEUED_MESSAGES.get();
            if (maximumMessages <= 0) {
                return;
            }

            while (outboundQueue.size() >= maximumMessages) {
                outboundQueue.removeLast();
                NtidBridgeMod.LOGGER.warn("NTID bridge outbound queue is full; dropping newest queued message");
            }
            outboundQueue.addFirst(payload);
        }
    }

    private void flushOutboundQueue() {
        int flushedMessages = 0;
        while (authenticated) {
            WebSocket current = socket;
            if (current == null) {
                break;
            }

            String payload;
            synchronized (outboundLock) {
                payload = outboundQueue.pollFirst();
            }
            if (payload == null) {
                break;
            }

            if (!sendNow(current, payload)) {
                break;
            }
            flushedMessages++;
        }

        if (flushedMessages > 0) {
            NtidBridgeMod.LOGGER.info("Flushed {} queued NTID bridge message(s)", flushedMessages);
        }
    }

    private void clearOutboundQueue() {
        synchronized (outboundLock) {
            outboundQueue.clear();
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        authenticated = false;
        NtidBridgeMod.LOGGER.info("NTID bridge connected");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        incoming.append(data);
        if (last) {
            String message = incoming.toString();
            incoming.setLength(0);
            handleText(message);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (socket == webSocket) {
            socket = null;
            authenticated = false;
        }
        NtidBridgeMod.LOGGER.info("NTID bridge closed: {} {}", statusCode, reason);
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (socket == webSocket) {
            socket = null;
            authenticated = false;
        }
        NtidBridgeMod.LOGGER.warn("NTID bridge socket error", error);
        scheduleReconnect();
    }

    private void handleText(String text) {
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            String type = stringValue(message, "type", "");
            switch (type) {
                case "hello" -> {
                    authenticated = true;
                    NtidBridgeMod.LOGGER.info("NTID bridge authenticated for {}", message.get("server"));
                    flushOutboundQueue();
                }
                case "pong", "minecraft.chat.sent", "minecraft.system.sent" -> {
                }
                case "discord.chat" -> runOnServer(() -> broadcastDiscordChat(message));
                case "backend.broadcast" -> runOnServer(() -> broadcastBackendMessage(message));
                case "backend.command" -> runOnServer(() -> executeBackendCommand(message));
                default -> NtidBridgeMod.LOGGER.debug("Ignoring NTID bridge message type {}", type);
            }
        } catch (RuntimeException error) {
            NtidBridgeMod.LOGGER.warn("Could not handle NTID bridge message: {}", text, error);
        }
    }

    private void runOnServer(Runnable task) {
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.execute(task);
        }
    }

    private void broadcastDiscordChat(JsonObject message) {
        String content = stringValue(message, "content", "");
        if (content.isBlank()) {
            return;
        }

        JsonObject sender = objectValue(message, "sender");
        String displayName = sender == null ? "Discord" : stringValue(sender, "displayName", "Discord");
        String kind = sender == null ? "" : stringValue(sender, "kind", "");
        ChatFormatting nameColor = "registered".equals(kind) ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE;

        Component line = Component.literal("[Discord] ")
                .withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(displayName).withStyle(nameColor))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(content).withStyle(ChatFormatting.WHITE));
        broadcast(line);
    }

    private void broadcastBackendMessage(JsonObject message) {
        String content = stringValue(message, "message", "");
        if (!content.isBlank()) {
            broadcast(Component.literal("[Server] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(content).withStyle(ChatFormatting.YELLOW)));
        }
    }

    private void executeBackendCommand(JsonObject message) {
        String requestId = stringValue(message, "requestId", "");
        JsonObject response = new JsonObject();
        response.addProperty("type", "backend.command.result");
        response.addProperty("requestId", requestId);

        try {
            String command = stringValue(message, "command", "");
            if (command.isBlank()) {
                throw new IllegalArgumentException("command is required");
            }

            String commandLine = commandLine(command, message.getAsJsonArray("args"));
            MinecraftServer currentServer = server;
            if (currentServer == null) {
                throw new IllegalStateException("server unavailable");
            }

            CommandSourceStack source = currentServer.createCommandSourceStack()
                    .withPermission(BridgeConfig.COMMAND_PERMISSION_LEVEL.get())
                    .withSuppressedOutput();
            currentServer.getCommands().performPrefixedCommand(source, commandLine);

            JsonObject resultObject = new JsonObject();
            resultObject.addProperty("output", "Command dispatched");
            response.addProperty("ok", true);
            response.add("result", resultObject);
        } catch (RuntimeException error) {
            response.addProperty("ok", false);
            response.addProperty("error", error.getMessage());
        }

        send(response);
    }

    private static String commandLine(String command, JsonArray args) {
        StringBuilder builder = new StringBuilder(command.startsWith("/") ? command.substring(1) : command);
        if (args != null) {
            for (int index = 0; index < args.size(); index++) {
                builder.append(' ').append(args.get(index).getAsString());
            }
        }
        return builder.toString();
    }

    private void broadcast(Component component) {
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.getPlayerList().broadcastSystemMessage(component, false);
        }
    }

    private static JsonObject objectValue(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonObject() ? object.getAsJsonObject(member) : null;
    }

    private static String stringValue(JsonObject object, String member, String fallback) {
        return object.has(member) && !object.get(member).isJsonNull() ? object.get(member).getAsString() : fallback;
    }
}
