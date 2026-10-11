// Copies the built plugin jar into launcher/plugin so it ships inside the npm package.
import { copyFileSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const libs = join(root, "..", "plugin", "build", "libs");
const jars = readdirSync(libs).filter((f) => /^MinecraftMCP-.*\.jar$/.test(f));
if (jars.length !== 1) {
  console.error(`Expected one plugin jar in ${libs}, found ${jars.length}. Run ./gradlew build in plugin/ first.`);
  process.exit(1);
}
const out = join(root, "plugin");
rmSync(out, { recursive: true, force: true });
mkdirSync(out);
copyFileSync(join(libs, jars[0]), join(out, jars[0]));
console.log(`Bundled ${jars[0]}`);
