package dev.minecraftmcp.schematic;

import dev.minecraftmcp.build.Box;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes Sponge Schematic v2 files (.schem), which WorldEdit and FastAsyncWorldEdit can paste.
 * Block entity contents (chest items, sign text) are not included.
 */
public final class SchematicWriter {

    private SchematicWriter() {
    }

    /**
     * @param states block state strings (e.g. "minecraft:oak_stairs[facing=north,...]") indexed
     *               as (y * length + z) * width + x, matching the Sponge layout.
     */
    public static void write(Path file, Box box, String[] states, int dataVersion) throws IOException {
        Map<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayOutputStream blockData = new ByteArrayOutputStream(states.length);
        for (String state : states) {
            int id = palette.computeIfAbsent(state, k -> palette.size());
            writeVarInt(blockData, id);
        }

        Nbt.Compound paletteTag = new Nbt.Compound();
        palette.forEach(paletteTag::put);

        Nbt.Compound root = new Nbt.Compound()
                .put("Version", 2)
                .put("DataVersion", dataVersion)
                .put("Width", (short) box.sizeX())
                .put("Height", (short) box.sizeY())
                .put("Length", (short) box.sizeZ())
                .put("Offset", new int[]{0, 0, 0})
                .put("PaletteMax", palette.size())
                .put("Palette", paletteTag)
                .put("BlockData", blockData.toByteArray())
                .put("BlockEntities", Nbt.EMPTY_LIST);

        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(file))))) {
            Nbt.writeRoot(out, "Schematic", root);
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
