package dev.minecraftmcp.mcp;

/** A JSON-RPC protocol error, as opposed to a tool failure the AI should read and react to. */
public final class RpcException extends RuntimeException {
    private final int code;

    public RpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
