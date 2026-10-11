#!/usr/bin/env node
// Creates and runs a sandbox Paper server with the MinecraftMCP plugin, so an AI can build
// in a copy of the player's world without touching the original save.

import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { homedir, platform } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const PACKAGE_ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DEFAULT_DIR = join(homedir(), ".minecraft-mcp", "sandbox");
const DEFAULT_VERSION = "26.2";
const MIN_JAVA = 25;
const USER_AGENT = "minecraft-mcp-launcher/0.1.0 (https://github.com/)";
const WORLD_FOLDERS = ["world", "world_nether", "world_the_end"];

// The classic superflat preset: bedrock, two dirt, grass. Without it the server logs an error
// and falls back to the same thing.
const FLAT_WORLD = {
  layers: [
    { block: "minecraft:bedrock", height: 1 },
    { block: "minecraft:dirt", height: 2 },
    { block: "minecraft:grass_block", height: 1 },
  ],
  biome: "minecraft:plains",
};

const HELP = `minecraft-mcp - let an AI build in a sandbox copy of your Minecraft world

Usage:
  minecraft-mcp worlds                    List your single-player worlds
  minecraft-mcp init [options]            Create the sandbox server
  minecraft-mcp start [options]           Start the sandbox server
  minecraft-mcp reset [options]           Throw away the AI's changes and re-copy the world

Options:
  --world <name|path>   Single-player world to copy (name from 'worlds', or a folder path).
                        Leave out for a fresh flat world.
  --version <mc>        Minecraft/Paper version (default ${DEFAULT_VERSION})
  --dir <path>          Sandbox folder (default ${DEFAULT_DIR})
  --port <n>            Minecraft port to join (default 25566)
  --mcp-port <n>        MCP endpoint port (default 25580)
  --memory <size>       Server memory, e.g. 2G (default 2G)
  --java <path>         Java ${MIN_JAVA}+ executable (default: java on PATH, then the
                        Java that the Minecraft launcher installs)
  --plugin <path>       Plugin jar to install (default: the bundled or locally built jar)
  --accept-eula         Accept the Minecraft EULA without prompting
  --yes                 Don't ask for confirmation in reset
`;

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    world: { type: "string" },
    version: { type: "string" },
    dir: { type: "string" },
    port: { type: "string" },
    "mcp-port": { type: "string" },
    memory: { type: "string", default: "2G" },
    java: { type: "string" },
    plugin: { type: "string" },
    "accept-eula": { type: "boolean", default: false },
    yes: { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
});

const dir = resolve(opts.dir ?? DEFAULT_DIR);
const metaFile = join(dir, "mcp-sandbox.json");

try {
  switch (positionals[0]) {
    case "worlds":
      listWorlds();
      break;
    case "init":
      await init();
      break;
    case "start":
      start();
      break;
    case "reset":
      await reset();
      break;
    default:
      console.log(HELP);
      process.exitCode = opts.help || !positionals[0] ? 0 : 1;
  }
} catch (err) {
  console.error(`\nError: ${err.message}`);
  process.exitCode = 1;
}

// ---- Commands ----

function listWorlds() {
  const saves = savesDir();
  if (!existsSync(saves)) {
    console.log(`No saves folder found at ${saves}`);
    return;
  }
  const worlds = readdirSync(saves, { withFileTypes: true })
    .filter((d) => d.isDirectory() && existsSync(join(saves, d.name, "level.dat")))
    .map((d) => d.name);
  console.log(worlds.length ? `Worlds in ${saves}:\n  ${worlds.join("\n  ")}` : `No worlds in ${saves}`);
}

async function init() {
  if (existsSync(metaFile)) {
    throw new Error(`A sandbox already exists in ${dir}. Use 'start', 'reset', or pass a different --dir.`);
  }
  const version = opts.version ?? DEFAULT_VERSION;
  const port = Number(opts.port ?? 25566);
  const mcpPort = Number(opts["mcp-port"] ?? 25580);
  const source = opts.world ? findWorld(opts.world) : null;
  const pluginJar = findPluginJar();

  const java = resolveJava();
  console.log(`Using Java: ${java}`);
  if (!opts["accept-eula"] && !(await confirm(
    "The Minecraft server requires you to accept the Minecraft EULA (https://aka.ms/MinecraftEULA). Do you accept?"))) {
    throw new Error("The EULA must be accepted to run a server.");
  }

  mkdirSync(join(dir, "plugins", "MinecraftMCP"), { recursive: true });
  writeFileSync(join(dir, "eula.txt"), "# Accepted via minecraft-mcp init\neula=true\n");

  await downloadPaper(version);
  copyFileSync(pluginJar, join(dir, "plugins", basename(pluginJar)));
  console.log(`Installed plugin ${basename(pluginJar)}`);

  if (source) copyWorld(source);

  writeServerProperties({ port, flat: !source });
  writePluginConfig(mcpPort);
  writeFileSync(metaFile, JSON.stringify({
    version, port, mcpPort, sourceWorld: source, createdAt: new Date().toISOString(),
  }, null, 2));

  console.log(`\nSandbox ready in ${dir}`);
  console.log("Run 'minecraft-mcp start' to launch it.");
}

function start() {
  const meta = readMeta();
  const jar = join(dir, "paper.jar");
  if (!existsSync(jar)) throw new Error(`No server jar in ${dir}. Run 'minecraft-mcp init' first.`);
  const java = resolveJava();
  console.log(`Using Java: ${java}`);

  // Refresh the plugin so a rebuilt jar is picked up without re-running init.
  const pluginJar = tryFindPluginJar();
  if (pluginJar) {
    const pluginsDir = join(dir, "plugins");
    for (const f of readdirSync(pluginsDir)) {
      if (/^MinecraftMCP-.*\.jar$/.test(f)) rmSync(join(pluginsDir, f));
    }
    copyFileSync(pluginJar, join(pluginsDir, basename(pluginJar)));
  }

  const url = `http://127.0.0.1:${meta.mcpPort}/mcp`;
  console.log(`
  Minecraft MCP sandbox (Minecraft ${meta.version})
  ------------------------------------------------
  1. In Minecraft ${meta.version}, open Multiplayer > Direct Connection > localhost:${meta.port}
  2. Connect your AI (once):
       claude mcp add --transport http minecraft ${url}
  3. In game: chat to give feedback, /ai stop to halt it, /ai for all controls.
  Type 'stop' here to shut the server down. Your original world is never modified.
`);

  const memory = opts.memory;
  const child = spawn(java, [`-Xms${memory}`, `-Xmx${memory}`, "-jar", "paper.jar", "--nogui"], {
    cwd: dir,
    stdio: "inherit",
  });
  child.on("exit", (code) => process.exit(code ?? 0));
  child.on("error", (err) => {
    console.error(`Could not start Java: ${err.message}`);
    process.exit(1);
  });
}

async function reset() {
  const meta = readMeta();
  const what = meta.sourceWorld ? `re-copy ${meta.sourceWorld}` : "generate a fresh world on next start";
  if (!opts.yes && !(await confirm(`This deletes everything built in the sandbox and will ${what}. Continue?`))) {
    return;
  }
  for (const folder of WORLD_FOLDERS) rmSync(join(dir, folder), { recursive: true, force: true });
  if (meta.sourceWorld) copyWorld(meta.sourceWorld);
  console.log("Sandbox world reset.");
}

// ---- Steps ----

async function downloadPaper(version) {
  console.log(`Looking up the latest Paper build for ${version}...`);
  const res = await fetch(`https://fill.papermc.io/v3/projects/paper/versions/${encodeURIComponent(version)}/builds/latest`,
    { headers: { "User-Agent": USER_AGENT } });
  if (!res.ok) throw new Error(`Paper has no builds for Minecraft ${version} (HTTP ${res.status}).`);
  const build = await res.json();
  const download = build.downloads?.["server:default"];
  if (!download) throw new Error(`Paper build ${build.id} has no server download.`);
  if (build.channel !== "STABLE") console.warn(`Note: Paper ${version} build ${build.id} is a ${build.channel} build.`);

  console.log(`Downloading ${download.name}...`);
  const jarRes = await fetch(download.url, { headers: { "User-Agent": USER_AGENT } });
  if (!jarRes.ok) throw new Error(`Download failed (HTTP ${jarRes.status}).`);
  const bytes = Buffer.from(await jarRes.arrayBuffer());
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (sha256 !== download.checksums.sha256) throw new Error("Downloaded Paper jar failed its checksum.");
  writeFileSync(join(dir, "paper.jar"), bytes);
}

function copyWorld(source) {
  if (existsSync(join(source, "session.lock"))) {
    console.warn("Tip: close the world in Minecraft before copying it, or the copy may miss recent changes.");
  }
  console.log(`Copying ${source} into the sandbox (the original is not modified)...`);
  // Single-player saves keep the Nether and End inside the world folder; Paper migrates them on first start.
  cpSync(source, join(dir, "world"), {
    recursive: true,
    filter: (src) => basename(src) !== "session.lock",
  });
}

function writeServerProperties({ port, flat }) {
  const props = {
    "server-ip": "127.0.0.1",
    "server-port": port,
    "level-name": "world",
    motd: "Minecraft MCP sandbox",
    gamemode: "creative",
    "force-gamemode": true,
    difficulty: "peaceful",
    "spawn-monsters": false,
    "allow-flight": true,
    "online-mode": true,
    "max-players": 8,
    ...(flat ? {
      "level-type": "minecraft\\:flat",
      "generator-settings": escapeProperty(JSON.stringify(FLAT_WORLD)),
      "generate-structures": false,
    } : {}),
  };
  const text = Object.entries(props).map(([k, v]) => `${k}=${v}`).join("\n");
  writeFileSync(join(dir, "server.properties"), `# Written by minecraft-mcp\n${text}\n`);
}

function escapeProperty(value) {
  return value.replace(/[\\:=]/g, (c) => `\\${c}`);
}

function writePluginConfig(mcpPort) {
  writeFileSync(join(dir, "plugins", "MinecraftMCP", "config.yml"), `# Written by minecraft-mcp for a local sandbox.
mcp:
  bind: 127.0.0.1
  port: ${mcpPort}
building:
  blocks-per-tick: 400
  approval-threshold: 2000
  approval-timeout-seconds: 120
  max-blocks-per-call: 200000
  undo-history: 50
tools:
  allow-run-command: true
allow-all-players: true
`);
}

// ---- Helpers ----

function savesDir() {
  switch (platform()) {
    case "win32":
      return join(process.env.APPDATA ?? join(homedir(), "AppData", "Roaming"), ".minecraft", "saves");
    case "darwin":
      return join(homedir(), "Library", "Application Support", "minecraft", "saves");
    default:
      return join(homedir(), ".minecraft", "saves");
  }
}

function findWorld(nameOrPath) {
  const candidates = [resolve(nameOrPath), join(savesDir(), nameOrPath)];
  const found = candidates.find((p) => existsSync(join(p, "level.dat")));
  if (!found) throw new Error(`Couldn't find a world '${nameOrPath}'. Run 'minecraft-mcp worlds' to list them.`);
  return found;
}

function tryFindPluginJar() {
  if (opts.plugin) return resolve(opts.plugin);
  for (const folder of [join(PACKAGE_ROOT, "plugin"), join(PACKAGE_ROOT, "..", "plugin", "build", "libs")]) {
    if (!existsSync(folder)) continue;
    const jar = readdirSync(folder).find((f) => /^MinecraftMCP-.*\.jar$/.test(f));
    if (jar) return join(folder, jar);
  }
  return null;
}

function findPluginJar() {
  const jar = tryFindPluginJar();
  if (!jar || !existsSync(jar)) {
    throw new Error("Plugin jar not found. Build it with './gradlew build' in plugin/, or pass --plugin <path>.");
  }
  return jar;
}

/**
 * Finds a Java new enough to run Paper: --java if given, otherwise java on PATH, otherwise the
 * private Java runtimes the official Minecraft launcher downloads (most players have these
 * without knowing it). Returns the executable path.
 */
function resolveJava() {
  if (opts.java) {
    const major = javaMajor(opts.java);
    if (major >= MIN_JAVA) return opts.java;
    throw new Error(`${opts.java} is Java ${major || "unknown"}, but Java ${MIN_JAVA}+ is required.`);
  }
  const candidates = ["java", ...launcherJavas()];
  for (const java of candidates) {
    if (javaMajor(java) >= MIN_JAVA) return java;
  }
  throw new Error(`Java ${MIN_JAVA}+ not found. Opening Minecraft ${DEFAULT_VERSION} once in the official launcher `
    + `downloads it, or install it from https://adoptium.net and pass --java <path>.`);
}

function launcherJavas() {
  const exe = platform() === "win32" ? "java.exe" : "java";
  const roots = platform() === "win32"
    ? [
        join(process.env["ProgramFiles(x86)"] ?? "C:\\Program Files (x86)", "Minecraft Launcher", "runtime"),
        join(process.env.APPDATA ?? "", ".minecraft", "runtime"),
        ...storeLauncherRuntimes(),
      ]
    : platform() === "darwin"
      ? [join(homedir(), "Library", "Application Support", "minecraft", "runtime")]
      : [join(homedir(), ".minecraft", "runtime")];

  // Layout: runtime/<component>/<platform>/<component>/bin/java (macOS adds jre.bundle/Contents/Home).
  const found = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const component of readdirSync(root)) {
      const componentDir = join(root, component);
      if (!existsSync(componentDir)) continue;
      for (const plat of readdirSync(componentDir)) {
        for (const home of [join(componentDir, plat, component), join(componentDir, plat, component, "jre.bundle", "Contents", "Home")]) {
          const java = join(home, "bin", exe);
          if (existsSync(java)) found.push(java);
        }
      }
    }
  }
  // Newest runtimes ("epsilon" > "delta" > ...) first.
  return found.sort().reverse();
}

function storeLauncherRuntimes() {
  const packages = join(process.env.LOCALAPPDATA ?? "", "Packages");
  if (!existsSync(packages)) return [];
  return readdirSync(packages)
    .filter((p) => p.startsWith("Microsoft.4297127D64EC6_"))
    .map((p) => join(packages, p, "LocalCache", "Local", "runtime"));
}

function javaMajor(java) {
  const result = spawnSync(java, ["-version"], { encoding: "utf8" });
  if (result.error) return 0;
  const match = /version "(\d+)/.exec(result.stderr + result.stdout);
  return match ? Number(match[1]) : 0;
}

function readMeta() {
  if (!existsSync(metaFile)) throw new Error(`No sandbox in ${dir}. Run 'minecraft-mcp init' first.`);
  return JSON.parse(readFileSync(metaFile, "utf8"));
}

async function confirm(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = await rl.question(`${question} (y/N) `);
    return /^y(es)?$/i.test(answer.trim());
  } finally {
    rl.close();
  }
}
