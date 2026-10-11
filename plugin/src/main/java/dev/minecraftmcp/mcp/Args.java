package dev.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Typed access to a tool call's arguments, with errors phrased for the AI to fix. */
public final class Args {
    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json;
    }

    public int integer(String name) throws ToolException {
        JsonElement el = require(name);
        try {
            double value = el.getAsDouble();
            if (value != Math.floor(value)) throw new ToolException("'" + name + "' must be a whole number");
            return (int) value;
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            throw new ToolException("'" + name + "' must be a number");
        }
    }

    public int integer(String name, int fallback) throws ToolException {
        return has(name) ? integer(name) : fallback;
    }

    public double number(String name, double fallback) throws ToolException {
        if (!has(name)) return fallback;
        try {
            return json.get(name).getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            throw new ToolException("'" + name + "' must be a number");
        }
    }

    public String string(String name) throws ToolException {
        JsonElement el = require(name);
        if (!el.isJsonPrimitive()) throw new ToolException("'" + name + "' must be a string");
        return el.getAsString();
    }

    public String string(String name, String fallback) throws ToolException {
        return has(name) ? string(name) : fallback;
    }

    public JsonArray array(String name) throws ToolException {
        JsonElement el = require(name);
        if (!el.isJsonArray()) throw new ToolException("'" + name + "' must be an array");
        return el.getAsJsonArray();
    }

    public boolean has(String name) {
        return json.has(name) && !json.get(name).isJsonNull();
    }

    private JsonElement require(String name) throws ToolException {
        if (!has(name)) throw new ToolException("Missing required argument '" + name + "'");
        return json.get(name);
    }
}
