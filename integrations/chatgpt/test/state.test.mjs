import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, chmod, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { State } from "../dist/state.js";
import { Operations } from "../dist/operations.js";
async function withState(fn) {
  const dir = await mkdtemp(join(tmpdir(), "distillpod-test-"));
  try {
    const s = new State(dir);
    await s.init();
    await fn(s);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}
test("private state rejects world-readable directories and overlapping processes", () =>
  withState(async (s) => {
    await s.lock(async () => {
      await assert.rejects(
        s.lock(async () => {}),
        (e) => e.code === "busy",
      );
    });
    await chmod(s.directory, 0o755);
    await assert.rejects(s.init(), (e) => e.code === "configuration");
  }));
test("durable operation retries retain IDs after unknown network outcome", () =>
  withState(async (s) => {
    let fail = true;
    const ids = [];
    const cloud = {
      config: { projectId: "demo-test" },
      uid: "alice",
      commit: async (m) => {
        ids.push(m.id);
        if (fail) {
          fail = false;
          throw Error("lost acknowledgement");
        }
        return { version: 1 };
      },
    };
    const o = new Operations(cloud, s);
    const proposal = await o.prepare([
      {
        key: "queue_library",
        baseVersion: 0,
        generation: "g",
        payload: { items: "" },
      },
    ]);
    await assert.rejects(o.apply(proposal.operationId));
    const r = await o.apply(proposal.operationId);
    assert.equal(r.status, "cloud_committed");
    assert.equal(ids[0], ids[1]);
  }));
test("staged metadata failure does not execute the queue mutation", () =>
  withState(async (s) => {
    const calls = [];
    const cloud = {
      config: { projectId: "demo-test" },
      uid: "alice",
      commit: async (m) => {
        calls.push(m.key);
        throw Error("failed");
      },
    };
    const o = new Operations(cloud, s);
    const p = await o.prepare([
      { key: "episode_a", baseVersion: 0, generation: "g", payload: {} },
      {
        key: "queue_library",
        baseVersion: 0,
        generation: "g",
        payload: { items: "a" },
      },
    ]);
    await assert.rejects(o.apply(p.operationId));
    assert.deepEqual(calls, ["episode_a"]);
  }));
test("different account cannot apply a journal", () =>
  withState(async (s) => {
    const cloud = { config: { projectId: "demo-test" }, uid: "alice" };
    const o = new Operations(cloud, s);
    const p = await o.prepare([]);
    cloud.uid = "bob";
    await assert.rejects(
      o.apply(p.operationId),
      (e) => e.code === "account_mismatch",
    );
  }));

test("installed runtime accepts a sibling private state but a source checkout does not", async () => {
  const { privateStateOutsideCheckout } = await import("../dist/state.js");
  const { mkdir } = await import("node:fs/promises");
  const base = await mkdtemp(join(tmpdir(), "distillpod-layout-"));
  try {
    const runtime = join(base, "share", "integration", "version");
    await mkdir(runtime, { recursive: true });
    assert.equal(
      privateStateOutsideCheckout(
        join(base, "share", "private-state"),
        runtime,
      ),
      true,
    );
    assert.equal(
      privateStateOutsideCheckout(join(runtime, "private-state"), runtime),
      false,
    );
    await mkdir(join(base, ".git"));
    assert.equal(
      privateStateOutsideCheckout(
        join(base, "share", "private-state"),
        runtime,
      ),
      false,
    );
  } finally {
    await rm(base, { recursive: true, force: true });
  }
});
