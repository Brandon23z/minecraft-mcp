package dev.minecraftmcp.mcp;

/** A tool result with text and an optional PNG image, for tools that show the AI a picture. */
public record ToolResult(String text, byte[] png) {
}
