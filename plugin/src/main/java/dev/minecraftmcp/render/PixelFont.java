package dev.minecraftmcp.render;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * A 3x5 bitmap font for coordinate labels. Built in so rendering never depends on system fonts,
 * which headless Linux servers often don't have.
 */
final class PixelFont {

    static final int SCALE = 2;
    static final int CHAR_W = 4 * SCALE; // 3 columns + 1 spacing
    static final int CHAR_H = 5 * SCALE;

    private static final Map<Character, String> GLYPHS = new HashMap<>();

    static {
        String[] defs = {
                "0111101101101111", "1010110010010111", "2111001111100111", "3111001111001111",
                "4101101111001001", "5111100111001111", "6111100111101111", "7111001001001001",
                "8111101111101111", "9111101111001111", "-000000111000000",
                "A010101111101101", "B110101110101110", "C011100100100011", "D110101101101110",
                "E111100110100111", "F111100110100100", "G011100101101011", "H101101111101101",
                "I111010010010111", "J001001001101010", "K101101110101101", "L100100100100111",
                "M101111111101101", "N110101101101101", "O010101101101010", "P110101110100100",
                "Q010101101110011", "R110101110101101", "S011100010001110", "T111010010010010",
                "U101101101101111", "V101101101101010", "W101101111111101", "X101101010101101",
                "Y101101010010010", "Z111001010100111", ":000010000010000", ".000000000000010",
                ",000000000010100", "(001010010010001", ")100010010010100", "=000111000111000",
                ">100010001010100", "<001010100010001", "/001001010100100", "+000010111010000",
                " 000000000000000",
        };
        for (String d : defs) GLYPHS.put(d.charAt(0), d.substring(1));
    }

    private PixelFont() {
    }

    static int width(String text) {
        return text.length() * CHAR_W - SCALE;
    }

    /** Draws text with a 1px dark outline so it stays readable over any background. */
    static void draw(BufferedImage img, String text, int x, int y, int rgb) {
        drawRaw(img, text, x, y, 0x101010, true);
        drawRaw(img, text, x, y, rgb, false);
    }

    private static void drawRaw(BufferedImage img, String text, int x, int y, int rgb, boolean outline) {
        int argb = 0xFF000000 | rgb;
        for (int i = 0; i < text.length(); i++) {
            String glyph = GLYPHS.getOrDefault(Character.toUpperCase(text.charAt(i)), GLYPHS.get(' '));
            int gx = x + i * CHAR_W;
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph.charAt(row * 3 + col) != '1') continue;
                    for (int py = 0; py < SCALE; py++) {
                        for (int px = 0; px < SCALE; px++) {
                            int cx = gx + col * SCALE + px, cy = y + row * SCALE + py;
                            if (outline) {
                                for (int oy = -1; oy <= 1; oy++) {
                                    for (int ox = -1; ox <= 1; ox++) set(img, cx + ox, cy + oy, argb);
                                }
                            } else {
                                set(img, cx, cy, argb);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void set(BufferedImage img, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) img.setRGB(x, y, argb);
    }
}
