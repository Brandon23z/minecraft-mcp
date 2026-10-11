package dev.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Holds the MCP tools and turns tool calls into MCP tool results. */
public final class ToolRegistry {

    @FunctionalInterface
    public interface Handler {
        /** Returns the text shown to the AI. Throw {@link ToolException} for errors it should see. */
        String call(Args args) throws Exception;
    }

    @FunctionalInterface
    public interface ImageHandler {
        /** Like {@link Handler}, but the result may include a picture. */
        ToolResult call(Args args) throws Exception;
    }

    private record Tool(String name, String description, JsonObject inputSchema, ImageHandler handler) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Logger logger;

    public ToolRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(String name, String description, Schema schema, Handler handler) {
        registerImage(name, description, schema, args -> new ToolResult(handler.call(args), null));
    }

    public void registerImage(String name, String description, Schema schema, ImageHandler handler) {
        tools.put(name, new Tool(name, description, schema.toJson(), handler));
    }

    public JsonObject listJson() {
        JsonArray list = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name());
            t.addProperty("description", tool.description());
            t.add("inputSchema", tool.inputSchema());
            list.add(t);
        }
        JsonObject result = new JsonObject();
        result.add("tools", list);
        return result;
    }

    public JsonObject call(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        Tool tool = tools.get(name);
        if (tool == null) throw new RpcException(-32602, "Unknown tool: " + name);
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        try {
            return result(tool.handler().call(new Args(arguments)), false);
        } catch (ToolException e) {
            return result(new ToolResult(e.getMessage(), null), true);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Tool " + name + " failed", e);
            return result(new ToolResult("Internal error in " + name + ": " + e, null), true);
        }
    }

    private static JsonObject result(ToolResult toolResult, boolean isError) {
        JsonArray contents = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", toolResult.text());
        contents.add(text);
        if (toolResult.png() != null) {
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("data", Base64.getEncoder().encodeToString(toolResult.png()));
            image.addProperty("mimeType", "image/png");
            contents.add(image);
        }
        JsonObject result = new JsonObject();
        result.add("content", contents);
        result.addProperty("isError", isError);
        return result;
    }
}
