import { randomUUID } from "node:crypto";
import { Cloud } from "./firestore.js";
import { State } from "./state.js";
import { queueChange, requireThat, type Mutation } from "./protocol.js";
export class Operations {
  constructor(
    public cloud: Cloud,
    public state: State,
  ) {}
  async prepare(mutations: Mutation[]) {
    const id = randomUUID();
    const proposal = {
      id,
      projectId: this.cloud.config.projectId,
      uid: this.cloud.uid,
      createdAt: Date.now(),
      started: false,
      mutations: mutations.map((m) => ({ ...m, id: randomUUID() })),
      completed: [] as any[],
    };
    await this.state.write(`operation-${id}.json`, proposal);
    return {
      operationId: id,
      changes: mutations,
      cloudOnly: true,
      phoneApplied: "unknown",
    };
  }
  async queue(action: string, args: string[]) {
    const { head, rows } = await this.cloud.snapshot();
    const q = rows.find((x) => x.key === "queue_library");
    const old = q?.payload.items ? q.payload.items.split(",") : [];
    const next = queueChange(old, action, args);
    requireThat(
      head.protocol >= 2,
      "upgrade_required",
      "Enable queue sync in Android",
    );
    requireThat(
      next.every((id) => rows.some((r) => r.payload.episodeId === id)),
      "missing_metadata",
      "Episode metadata unavailable; inspect the public feed first",
    );
    if (next.join(",") === old.join(",")) return { noOp: true };
    const identities = next
      .filter((id) => !rows.some((r) => r.key === `episode_${id}`))
      .map((id) => {
        const p = rows.find((r) => r.payload.episodeId === id)!.payload;
        const fields = [
          "episodeId",
          "podcastId",
          "feedUrl",
          "originalFeedUrl",
          "podcastTitle",
          "podcastAuthor",
          "guid",
          "title",
          "audioUrl",
          "durationMs",
          "fingerprint",
        ];
        return {
          key: `episode_${id}`,
          baseVersion: 0,
          generation: head.generation,
          payload: Object.fromEntries(fields.map((k) => [k, p[k]])),
        };
      });
    return this.prepare([
      ...identities,
      {
        key: "queue_library",
        baseVersion: q?.version ?? 0,
        payload: { items: next.join(",") },
        generation: head.generation,
      },
    ]);
  }
  async apply(id: string) {
    requireThat(/^[a-f0-9-]{36}$/.test(id), "usage", "Invalid operation ID");
    const p = await this.state.read(`operation-${id}.json`);
    requireThat(
      p &&
        p.projectId === this.cloud.config.projectId &&
        p.uid === this.cloud.uid,
      "account_mismatch",
      "Operation belongs to another account",
    );
    requireThat(
      p.started || Date.now() - p.createdAt < 86400000,
      "expired",
      "Proposal expired; prepare again",
    );
    p.started = true;
    await this.state.write(`operation-${id}.json`, p);
    for (let i = p.completed.length; i < p.mutations.length; i++) {
      const result = await this.cloud.commit(p.mutations[i]);
      p.completed.push(result);
      await this.state.write(`operation-${id}.json`, p);
    }
    return {
      operationId: id,
      status: "cloud_committed",
      receipts: p.completed,
      phoneApplied: "unknown",
      warning:
        "Private phone queue entries may remain; current playback is not stopped.",
    };
  }
}
