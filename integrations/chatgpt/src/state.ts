import { mkdir, open, readFile, rename, lstat, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { Failure, requireThat } from "./protocol.js";
export function privateStateOutsideCheckout(
  directory: string,
  packageRoot: string,
): boolean {
  let root = resolve(packageRoot);
  for (let parent = root; ; parent = dirname(parent)) {
    if (existsSync(join(parent, ".git"))) {
      root = parent;
      break;
    }
    if (parent === dirname(parent)) break;
  }
  const candidate = resolve(directory);
  return candidate !== root && !candidate.startsWith(root + "/");
}
export class State {
  constructor(public directory: string) {
    requireThat(
      resolve(directory) === directory,
      "configuration",
      "--private-state must be an absolute private directory outside the checkout",
    );
  }
  async init() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const s = await lstat(this.directory);
    requireThat(
      s.isDirectory() &&
        !s.isSymbolicLink() &&
        (s.mode & 0o077) === 0 &&
        s.uid === process.getuid?.(),
      "configuration",
      "Private state must be owner-only (0700) and owned by this user",
    );
  }
  async read(name: string) {
    try {
      const s = await lstat(join(this.directory, name));
      requireThat(
        s.isFile() && !s.isSymbolicLink() && (s.mode & 0o077) === 0,
        "configuration",
        "State file permissions must be 0600",
      );
      return JSON.parse(await readFile(join(this.directory, name), "utf8"));
    } catch (e: any) {
      if (e.code === "ENOENT") return null;
      throw e;
    }
  }
  async write(name: string, data: unknown) {
    const tmp = join(this.directory, `${name}.${randomUUID()}.tmp`);
    const f = await open(tmp, "wx", 0o600);
    try {
      await f.writeFile(JSON.stringify(data));
      await f.sync();
    } finally {
      await f.close();
    }
    await rename(tmp, join(this.directory, name));
  }
  async remove(name: string) {
    await rm(join(this.directory, name), { force: true });
  }
  async lock<T>(fn: () => Promise<T>) {
    let lock;
    try {
      lock = await open(join(this.directory, "client.lock"), "wx", 0o600);
    } catch {
      throw new Failure(
        "busy",
        "Another client may be running. If interrupted, verify it stopped before removing client.lock.",
      );
    }
    try {
      await lock.writeFile(String(process.pid));
      return await fn();
    } finally {
      await lock.close();
      await this.remove("client.lock");
    }
  }
}
