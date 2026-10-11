package dev.minecraftmcp.schematic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough of an NBT writer to produce Sponge schematic files. */
final class Nbt {

    private static final byte END = 0, SHORT = 2, INT = 3, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11;

    /** A compound tag whose values are Short, Integer, String, byte[], int[], Compound or EmptyList. */
    static final class Compound {
        final Map<String, Object> values = new LinkedHashMap<>();

        Compound put(String name, Object value) {
            values.put(name, value);
            return this;
        }
    }

    /** An empty list of compounds, which is all a schematic without block entities needs. */
    static final Object EMPTY_LIST = new Object();

    private Nbt() {
    }

    static void writeRoot(DataOutputStream out, String name, Compound root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(name);
        writeCompoundBody(out, root);
    }

    private static void writeCompoundBody(DataOutputStream out, Compound compound) throws IOException {
        for (Map.Entry<String, Object> e : compound.values.entrySet()) {
            Object v = e.getValue();
            out.writeByte(typeOf(v));
            out.writeUTF(e.getKey());
            writePayload(out, v);
        }
        out.writeByte(END);
    }

    private static byte typeOf(Object v) {
        if (v instanceof Short) return SHORT;
        if (v instanceof Integer) return INT;
        if (v instanceof String) return STRING;
        if (v instanceof byte[]) return BYTE_ARRAY;
        if (v instanceof int[]) return INT_ARRAY;
        if (v instanceof Compound) return COMPOUND;
        if (v == EMPTY_LIST) return LIST;
        throw new IllegalArgumentException("Unsupported NBT value: " + v);
    }

    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case String s -> out.writeUTF(s);
            case byte[] bytes -> {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case int[] ints -> {
                out.writeInt(ints.length);
                for (int i : ints) out.writeInt(i);
            }
            case Compound c -> writeCompoundBody(out, c);
            default -> {
                out.writeByte(COMPOUND);
                out.writeInt(0);
            }
        }
    }
}
