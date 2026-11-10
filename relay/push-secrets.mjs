// ../secrets.properties 에서 중계 서버용 값만 골라 Cloudflare 비밀값으로 올린다.
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const props = Object.fromEntries(
  readFileSync(new URL("../secrets.properties", import.meta.url), "utf8")
    .split(/\r?\n/)
    .filter((l) => l.includes("=") && !l.trimStart().startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]),
);
const wanted = { ANTHROPIC_API_KEY: props["anthropic.apiKey"], APP_TOKEN: props["relay.token"] };

for (const [name, value] of Object.entries(wanted)) {
  if (!value) throw new Error(`secrets.properties 에 ${name} 값이 비어 있음`);
  const r = spawnSync("npx", ["wrangler", "secret", "put", name], { input: value, shell: true, stdio: ["pipe", "inherit", "inherit"] });
  if (r.status !== 0) process.exit(r.status ?? 1);
}
