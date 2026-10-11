package dev.minecraftmcp.mcp;

/** A tool failure whose message is returned to the AI as an error result it can act on. */
public final class ToolException extends Exception {
    public ToolException(String message) {
        super(message);
    }
}
