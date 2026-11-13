import {
  Failure,
  requireThat,
  signature,
  validateMutation,
  type Mutation,
} from "./protocol.js";
export interface Config {
  projectId: string;
  apiKey: string;
  clientId?: string;
  clientSecret?: string;
  emulator?: boolean;
  firestoreHost?: string;
  authHost?: string;
}
export interface RecordValue {
  key: string;
  version: number;
  payload: Record<string, string>;
  deleted: boolean;
  generation: string;
  sequence: number;
}
export function encode(v: any): any {
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number") return { integerValue: String(v) };
  return {
    mapValue: {
      fields: Object.fromEntries(
        Object.entries(v).map(([k, x]) => [k, encode(x)]),
      ),
    },
  };
}
export function decode(v: any): any {
  if ("stringValue" in v) return v.stringValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("booleanValue" in v) return v.booleanValue;
  return Object.fromEntries(
    Object.entries(v.mapValue?.fields ?? {}).map(([k, x]) => [k, decode(x)]),
  );
}
export class Cloud {
  readonly root: string;
  readonly url: string;
  constructor(
    public config: Config,
    public uid: string,
    private token: () => Promise<string>,
    private transport: typeof fetch = fetch,
  ) {
    requireThat(
      /^[A-Za-z0-9_-]+$/.test(uid) && /^[a-z0-9-]+$/.test(config.projectId),
      "configuration",
      "Invalid project or account",
    );
    if (config.emulator) {
      requireThat(
        config.projectId.startsWith("demo-") &&
          /^(127\.0\.0\.1|localhost):\d+$/.test(config.firestoreHost ?? ""),
        "configuration",
        "Demo emulator requires explicit loopback host",
      );
    } else
      requireThat(
        !config.firestoreHost &&
          !config.authHost &&
          !config.projectId.startsWith("demo-"),
        "configuration",
        "Production/emulator configuration mismatch",
      );
    this.root = `projects/${config.projectId}/databases/(default)/documents/users/${uid}`;
    this.url = config.emulator
      ? `http://${config.firestoreHost}/v1/`
      : "https://firestore.googleapis.com/v1/";
  }
  async request(path: string, body?: unknown, method = body ? "POST" : "GET") {
    const r = await this.transport(this.url + path, {
      method,
      headers: {
        Authorization: `Bearer ${await this.token()}`,
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    });
    if (r.status === 404 && method === "GET") return null;
    if (!r.ok) {
      const code =
        r.status === 409 || r.status === 412
          ? "contention"
          : r.status === 401
            ? "auth_required"
            : r.status === 403
              ? "permission_denied"
              : r.status === 429
                ? "quota"
                : "service";
      throw new Failure(
        code,
        `Firestore request failed (${r.status}); no success is established`,
      );
    }
    return r.json();
  }
  async get(path: string, transaction?: string) {
    const result = transaction
      ? await this.request(
          `${this.root.split("/documents/")[0]}/documents:batchGet`,
          { documents: [path], transaction },
        )
      : null;
    const d = transaction ? result?.[0]?.found : await this.request(path);
    return d?.fields ? decode({ mapValue: { fields: d.fields } }) : null;
  }
  async head(tx?: string) {
    const h = await this.get(this.root, tx);
    requireThat(
      h?.active &&
        typeof h.generation === "string" &&
        [1, 2, 3].includes(h.protocol) &&
        Number.isSafeInteger(h.sequence),
      "account_unavailable",
      "Existing active supported library required",
    );
    return h;
  }
  async snapshot(limit = 1000) {
    requireThat(
      Number.isInteger(limit) && limit >= 1 && limit <= 5000,
      "usage",
      "Read limit must be 1–5000",
    );
    const head = await this.head();
    const rows: RecordValue[] = [];
    let page = "";
    do {
      const q = new URLSearchParams({
        pageSize: String(Math.min(100, limit - rows.length)),
      });
      if (page) q.set("pageToken", page);
      const r = await this.request(`${this.root}/entities?${q}`);
      for (const d of r?.documents ?? [])
        rows.push({
          ...decode({ mapValue: { fields: d.fields } }),
          key: d.name.split("/").at(-1),
        });
      page = r?.nextPageToken ?? "";
    } while (page && rows.length < limit);
    const end = await this.head();
    requireThat(
      head.generation === end.generation && head.sequence === end.sequence,
      "unstable_read",
      "Library changed during read; retry",
    );
    requireThat(
      !page,
      "incomplete_read",
      "Read budget reached; increase --limit to inspect this library",
    );
    return {
      head,
      rows: rows.filter((r) => !r.deleted),
      observedAt: new Date().toISOString(),
      scope: "cloud_synced",
      complete: true,
    };
  }
  async commit(m: Mutation) {
    requireThat(
      /^[A-Za-z0-9_-]{1,128}$/.test(m.key) &&
        /^[A-Za-z0-9_-]{1,128}$/.test(m.id ?? "") &&
        Number.isSafeInteger(m.baseVersion) &&
        m.baseVersion >= 0,
      "invalid_data",
      "Invalid operation identity",
    );
    const db = this.root.split("/documents/")[0];
    for (let attempt = 0; attempt < 4; attempt++) {
      let observed = -1;
      try {
        // Firebase user-token clients use optimistic commits. A root updateTime
        // precondition covers every entity read because all sync writes advance
        // this root; entity and create preconditions protect each companion too.
        const root = await this.request(this.root);
        const h = root?.fields
          ? decode({ mapValue: { fields: root.fields } })
          : null;
        requireThat(
          h?.active &&
            h.generation === m.generation &&
            [1, 2, 3].includes(h.protocol) &&
            Number.isSafeInteger(h.sequence) &&
            typeof root.updateTime === "string",
          "account_mismatch",
          "Existing active matching library required",
        );
        observed = h.sequence;
        validateMutation(m.key, m.payload, h.protocol);
        const document = await this.request(`${this.root}/entities/${m.key}`);
        const entity = document?.fields
          ? decode({ mapValue: { fields: document.fields } })
          : null;
        const receipt = await this.get(`${this.root}/receipts/${m.id}`);
        if (receipt) {
          requireThat(
            receipt.signature === signature(m),
            "operation_mismatch",
            "Operation ID reused with different content",
          );
          return receipt;
        }
        requireThat(
          (entity?.version ?? 0) === m.baseVersion,
          "conflict",
          "Cloud record changed; review a fresh proposal",
        );
        const sequence = h.sequence + 1;
        const data = {
          version: m.baseVersion + 1,
          payload: m.payload,
          deleted: false,
          generation: m.generation,
          sequence,
          operationId: m.id,
        };
        const result = {
          signature: signature(m),
          key: m.key,
          version: data.version,
          sequence,
          generation: m.generation,
        };
        const update = (name: string, value: any, currentDocument: any) => ({
          update: { name, fields: encode(value).mapValue.fields },
          currentDocument,
        });
        await this.request(`${db}/documents:commit`, {
          writes: [
            {
              ...update(
                this.root,
                { sequence },
                { updateTime: root.updateTime },
              ),
              updateMask: { fieldPaths: ["sequence"] },
            },
            update(
              `${this.root}/entities/${m.key}`,
              data,
              document
                ? { updateTime: document.updateTime }
                : { exists: false },
            ),
            update(
              `${this.root}/changes/${sequence}`,
              { ...data, key: m.key },
              { exists: false },
            ),
            update(`${this.root}/receipts/${m.id}`, result, { exists: false }),
          ],
        });
        return result;
      } catch (e) {
        if (
          e instanceof Failure &&
          attempt < 3 &&
          (e.code === "contention" || e.code === "permission_denied")
        ) {
          if (e.code === "contention") {
            await new Promise((resolve) =>
              setTimeout(resolve, 50 * (attempt + 1)),
            );
            continue;
          }
          const latest = await this.head();
          if (latest.generation === m.generation && latest.sequence > observed)
            continue;
        }
        throw e;
      }
    }
    throw new Failure("contention", "Cloud busy; retry the same operation");
  }
}
