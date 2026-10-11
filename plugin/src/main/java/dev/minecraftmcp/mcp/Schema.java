package dev.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small builder for the JSON Schema objects that describe tool inputs. */
public final class Schema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static Schema object() {
        return new Schema();
    }

    public Schema integer(String name, String description, boolean isRequired) {
        return add(name, prop("integer", description), isRequired);
    }

    public Schema number(String name, String description, boolean isRequired) {
        return add(name, prop("number", description), isRequired);
    }

    public Schema string(String name, String description, boolean isRequired) {
        return add(name, prop("string", description), isRequired);
    }

    public Schema stringEnum(String name, String description, boolean isRequired, String... values) {
        JsonObject p = prop("string", description);
        JsonArray options = new JsonArray();
        for (String v : values) options.add(v);
        p.add("enum", options);
        return add(name, p, isRequired);
    }

    public Schema array(String name, String description, boolean isRequired, JsonObject items) {
        JsonObject p = prop("array", description);
        p.add("items", items);
        return add(name, p, isRequired);
    }

    /** Adds the six corner coordinates (x1, y1, z1, x2, y2, z2) of a box. */
    public Schema box() {
        for (String axis : new String[]{"x", "y", "z"}) {
            integer(axis + "1", "First corner " + axis.toUpperCase() + " (inclusive)", true);
        }
        for (String axis : new String[]{"x", "y", "z"}) {
            integer(axis + "2", "Opposite corner " + axis.toUpperCase() + " (inclusive)", true);
        }
        return this;
    }

    public Schema world() {
        return string("world", "World name. Defaults to the main overworld.", false);
    }

    public JsonObject toJson() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) schema.add("required", required);
        return schema;
    }

    private Schema add(String name, JsonObject prop, boolean isRequired) {
        properties.add(name, prop);
        if (isRequired) required.add(name);
        return this;
    }

    private static JsonObject prop(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }
}
