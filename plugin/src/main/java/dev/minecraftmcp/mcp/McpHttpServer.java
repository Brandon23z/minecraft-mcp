package dev.minecraftmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A minimal MCP server using the Streamable HTTP transport.
 *
 * Every JSON-RPC request is answered with a single application/json response, which the
 * spec allows, so no SSE stream is needed. The server only listens on the configured bind
 * address (127.0.0.1 by default) and rejects browser requests from non-local origins to
 * block DNS rebinding attacks.
 */
public final class McpHttpServer {

    /** Newest first. The first entry is offered when the client asks for an unknown version. */
    private static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Gson GSON = new Gson();

    private final ToolRegistry tools;
    private final Logger logger;
    private final String serverVersion;
    private final String instructions;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;

    public McpHttpServer(ToolRegistry tools, Logger logger, String serverVersion, String instructions) {
        this.tools = tools;
        this.logger = logger;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
    }

    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/mcp", this::handle);
        AtomicInteger threadId = new AtomicInteger();
        // Tool calls block while blocks are placed or a player approves, so use a growable pool.
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MinecraftMCP-http-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void handle(HttpExchange ex) {
        try {
            if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
                send(ex, 403, rpcError(null, -32000, "Forbidden origin"));
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> handlePost(ex);
                case "DELETE" -> {
                    String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
                    if (session != null) sessions.remove(session);
                    send(ex, 200, null);
                }
                default -> {
                    // GET would open a server-to-client SSE stream, which this server doesn't offer.
                    ex.getResponseHeaders().set("Allow", "POST, DELETE");
                    send(ex, 405, null);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "MCP request failed", e);
            try {
                send(ex, 500, rpcError(null, -32603, "Internal error"));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private void handlePost(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            send(ex, 400, rpcError(null, -32700, "Parse error"));
            return;
        }

        if (parsed.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    responses.add(rpcError(null, -32600, "Invalid request"));
                    continue;
                }
                JsonObject response = dispatch(el.getAsJsonObject(), ex);
                if (response != null) responses.add(response);
            }
            if (responses.isEmpty()) send(ex, 202, null);
            else send(ex, 200, responses);
        } else if (parsed.isJsonObject()) {
            JsonObject response = dispatch(parsed.getAsJsonObject(), ex);
            if (response == null) send(ex, 202, null);
            else send(ex, 200, response);
        } else {
            send(ex, 400, rpcError(null, -32600, "Invalid request"));
        }
    }

    /** Returns the JSON-RPC response, or null for notifications and client responses. */
    private JsonObject dispatch(JsonObject msg, HttpExchange ex) {
        if (!msg.has("method")) return null;
        JsonElement id = msg.get("id");
        if (id == null || id.isJsonNull()) return null; // Notifications need no handling here.

        String method = msg.get("method").getAsString();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params")
                : new JsonObject();
        try {
            JsonElement result = switch (method) {
                case "initialize" -> {
                    String session = UUID.randomUUID().toString();
                    sessions.add(session);
                    ex.getResponseHeaders().set("Mcp-Session-Id", session);
                    yield initialize(params);
                }
                case "ping" -> new JsonObject();
                case "tools/list" -> tools.listJson();
                case "tools/call" -> tools.call(params);
                default -> throw new RpcException(-32601, "Method not found: " + method);
            };
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        } catch (RpcException e) {
            return rpcError(id, e.code(), e.getMessage());
        } catch (Exception e) {
            logger.log(Level.WARNING, "MCP method " + method + " failed", e);
            return rpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.getFirst();

        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCap);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "minecraft-mcp");
        serverInfo.addProperty("title", "Minecraft MCP");
        serverInfo.addProperty("version", serverVersion);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", instructions);
        return result;
    }

    private static boolean originAllowed(String origin) {
        if (origin == null || origin.isEmpty()) return true; // Not a browser request.
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        return response;
    }

    private static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
