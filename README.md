# Minecraft MCP

Let an AI assistant build in your Minecraft world while you watch from inside the game.

**Works with any MCP client:** Claude Code, OpenAI Codex CLI, Gemini CLI, Cursor, VS Code (GitHub Copilot), Claude Desktop, and more. See [Connect your AI](#connect-your-ai).

There's no bot player. A Paper server plugin exposes an [MCP](https://modelcontextprotocol.io) endpoint, and the AI's tools edit the world directly through the server API. Everything happens on a **sandbox server** running either a fresh world or a copy of one of your saves, so your real saves are never touched. When you like a build, export it as a schematic and paste it into your real world with WorldEdit.

```
Your AI app ──MCP (HTTP)──► MinecraftMCP plugin ──► sandbox Paper server ◄── you, in game
```

## What you can do in game

| Command | What it does |
|---|---|
| *(chat)* | Everything you say in chat is passed to the AI, so you can steer it |
| `/ai stop [what went wrong]` | Halt immediately. Queued building is cancelled and the AI is told why |
| `/ai resume` | Let it continue |
| `/ai approve` / `/ai deny [reason]` | Answer a large-edit request (clickable buttons appear in chat) |
| `/ai undo [steps]` | Revert the AI's most recent edits |
| `/ai zone <radius>` / `off` / `show` | Only allow building within a radius of where you stand |
| `/ai status` | What the AI is doing right now |

Blocks are placed a few hundred per tick, so you see the build go up and can stop it partway through. Edits larger than 2,000 blocks show a yellow outline and wait for you to approve them.

## AI tools

| Tool | Purpose |
|---|---|
| `get_world_state` | Worlds, players, and AI status |
| `get_player_context` | Where you're standing and what you're looking at ("build here") |
| `get_messages` | Your chat and control events since the last check |
| `read_region` | The blocks in a box, as a palette plus one grid per layer |
| `get_surface` | Terrain heights for finding flat ground |
| `render_view` | **A picture of the build** (isometric 3D, top-down, or any side), labelled with coordinates, so the AI can see what it built and fix it |
| `set_blocks` | Place individual blocks (stairs, doors, details) |
| `fill` | Solid, hollow, outline, or replace fills |
| `line` | Straight lines in any direction, including diagonals |
| `cylinder` | Towers, pillars, tunnels and arches (upright or on its side, solid or hollow) |
| `sphere` | Spheres, domes and bowls |
| `roof` | Gable or hip roofs of stairs, with correct facing and corner shapes |
| `copy_region` | Repeat a finished part elsewhere, rotated or mirrored, with directional blocks turned to match |
| `preview` | Particle outline of a box, to confirm a location |
| `undo` | Revert the AI's edits |
| `export_schematic` | Save a build as `.schem` for WorldEdit |
| `run_command` | Run server commands like `time set day` (sandbox only, `stop`/`op`/`ban` etc. are blocked) |

## Quick start

Requirements: **Node.js 20+** and Minecraft Java Edition at the same version as the sandbox (default 26.2).

The server needs **Java 25+**. You probably already have it: the official Minecraft launcher downloads its own private Java, and the launcher finds it automatically. If it can't, install Java from [Adoptium](https://adoptium.net) (a free OpenJDK build) and pass `--java <path>`.

Building the plugin from source needs a Java 25 JDK. The Minecraft launcher's copy works. On Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files (x86)\Minecraft Launcher\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon"
```

```sh
# 1. Build the plugin
cd plugin
./gradlew build          # Windows: .\gradlew.bat build

# 2. Create the sandbox: a fresh flat world...
cd ../launcher
node bin/minecraft-mcp.js init
#    ...or a copy of one of your saves (the original is only read, never modified)
node bin/minecraft-mcp.js worlds
node bin/minecraft-mcp.js init --world "My World"

# 3. Start it
node bin/minecraft-mcp.js start
```

4. Connect your AI app (one time). See [Connect your AI](#connect-your-ai).
5. Join `localhost:25566` in Minecraft, start a **new** session of your AI app, and ask something like *"build a small oak cabin where I'm looking"*.

You won't see a bot join. The AI works through the server, so you'll see blocks appear and `[AI]` messages in chat.

To throw away everything the AI built and start over: `node bin/minecraft-mcp.js reset`.

## Connect your AI

The sandbox exposes a standard MCP endpoint (Streamable HTTP):

```
http://127.0.0.1:25580/mcp
```

Add it to your AI app once, then **start a new session**. Most apps only load MCP servers when a session starts.

### Claude Code

```sh
claude mcp add --transport http --scope user minecraft http://127.0.0.1:25580/mcp
```

`--scope user` makes it available in every folder. Check with `/mcp` inside a session.

### OpenAI Codex CLI

`~/.codex/config.toml`:

```toml
[mcp_servers.minecraft]
url = "http://127.0.0.1:25580/mcp"
```

### Gemini CLI

`~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "minecraft": { "httpUrl": "http://127.0.0.1:25580/mcp" }
  }
}
```

### Cursor

`~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "minecraft": { "url": "http://127.0.0.1:25580/mcp" }
  }
}
```

### VS Code (GitHub Copilot agent mode)

`.vscode/mcp.json` in your workspace:

```json
{
  "servers": {
    "minecraft": { "type": "http", "url": "http://127.0.0.1:25580/mcp" }
  }
}
```

### Claude Desktop and other apps that can't connect to a URL

Some apps can only start a local program. Bridge to the endpoint with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote), for example in Claude Desktop's `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "minecraft": { "command": "npx", "args": ["-y", "mcp-remote", "http://127.0.0.1:25580/mcp"] }
  }
}
```

> Config formats change between app versions. If one of these doesn't work, check that app's MCP documentation and point it at the URL above.

### What to expect from different AIs

- **Tested with:** Claude Code. Other apps follow the same MCP standard and should work. Please open an issue if one doesn't.
- **The model matters more than the app.** Building takes 3D planning and many tool calls in a row. Strong models do well. Small local models can connect but tend to build poorly.
- **The safety controls apply to every AI.** Approvals, `/ai stop`, undo and the build zone are enforced by the server, not by the AI.
- **Guidance:** the server sends usage guidance when an AI connects (check where the player is, read chat between steps, stop when told). Some apps don't pass this to the model, so the key rules are also in each tool's description.

## Using the plugin on an existing server

Drop `plugin/build/libs/MinecraftMCP-*.jar` into a Paper server's `plugins/` folder. The defaults in `plugins/MinecraftMCP/config.yml` are conservative: only ops can use `/ai`, `run_command` is off, and the endpoint listens on `127.0.0.1` only.

**Security:** anything that can reach the MCP port can edit the world. Keep `mcp.bind` on `127.0.0.1`. The server also rejects browser requests from non-local origins.

## Project layout

```
plugin/     Paper plugin (Java 25, Gradle). Embeds the MCP server, no extra dependencies.
  mcp/        JSON-RPC + Streamable HTTP transport, tool registry
  build/      Throttled build queue with undo journal
  control/    Stop/resume, approvals, build zone, chat inbox
  tools/      The MCP tools (world, shapes, rendering)
  render/     Top-down, side and isometric renderer with coordinate labels
  schematic/  Sponge .schem writer
launcher/   Node CLI that downloads Paper, copies your world, and runs the sandbox
```

## Roadmap ideas

- Ghost-block previews using block display entities
- Player-made templates: save a build by hand and let the AI study and reuse it
- A building style guide the AI reads before it starts
- Block entity contents (chest items, sign text) in undo and schematics
- Live progress notifications over an SSE stream
- Fabric mod backend and a Bedrock backend

## License

MIT
