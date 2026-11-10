import { createServer } from "node:http";
import { randomBytes, createHash } from "node:crypto";
import { createRemoteJWKSet, jwtVerify } from "jose";
import { State } from "./state.js";
import { Failure, requireThat } from "./protocol.js";
import type { Config } from "./firestore.js";
async function post(url: string, body: any, form = false) {
  const r = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": form
        ? "application/x-www-form-urlencoded"
        : "application/json",
    },
    body: form ? new URLSearchParams(body) : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  requireThat(
    r.ok,
    "auth_required",
    `Authentication failed (${r.status}); reconnect privately`,
  );
  return r.json();
}
export class Auth {
  constructor(
    public config: Config,
    public state: State,
  ) {}
  endpoint(path: string) {
    if (this.config.emulator) {
      requireThat(
        this.config.projectId.startsWith("demo-") &&
          /^(127\.0\.0\.1|localhost):\d+$/.test(this.config.authHost ?? ""),
        "configuration",
        "Explicit demo Auth endpoint required",
      );
      return `http://${this.config.authHost}/identitytoolkit.googleapis.com/v1/${path}?key=demo-key`;
    }
    return `https://identitytoolkit.googleapis.com/v1/${path}?key=${encodeURIComponent(this.config.apiKey)}`;
  }
  async save(r: any) {
    requireThat(
      r.localId && r.idToken && r.refreshToken,
      "auth_required",
      "Incomplete sign-in response",
    );
    const old = await this.state.read("auth.json");
    requireThat(
      !old ||
        (old.uid === r.localId && old.projectId === this.config.projectId),
      "account_mismatch",
      "Logout before changing account",
    );
    await this.state.write("auth.json", {
      projectId: this.config.projectId,
      uid: r.localId,
      idToken: r.idToken,
      refreshToken: r.refreshToken,
      expiresAt: Date.now() + Number(r.expiresIn) * 1000,
    });
    return { signedIn: true };
  }
  async emulatorLogin(email: string) {
    requireThat(
      this.config.emulator,
      "configuration",
      "Emulator sign-in requires demo configuration",
    );
    return this.save(
      await post(this.endpoint("accounts:signUp"), {
        email,
        password: randomBytes(24).toString("hex"),
        returnSecureToken: true,
      }),
    );
  }
  async token() {
    const a = await this.state.read("auth.json");
    requireThat(
      a && a.projectId === this.config.projectId,
      "auth_required",
      "Run auth login on this computer",
    );
    if (a.expiresAt > Date.now() + 60000) return a.idToken as string;
    requireThat(
      !this.config.emulator,
      "auth_required",
      "Reconnect the disposable emulator account",
    );
    const r = await post(
      `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(this.config.apiKey)}`,
      { grant_type: "refresh_token", refresh_token: a.refreshToken },
      true,
    );
    requireThat(
      r.user_id === a.uid,
      "account_mismatch",
      "Refreshed identity changed",
    );
    await this.state.write("auth.json", {
      ...a,
      idToken: r.id_token,
      refreshToken: r.refresh_token,
      expiresAt: Date.now() + Number(r.expires_in) * 1000,
    });
    return r.id_token as string;
  }
  async login() {
    requireThat(
      !this.config.emulator && this.config.clientId,
      "configuration",
      "Desktop OAuth clientId required",
    );
    const verifier = randomBytes(32).toString("base64url"),
      state = randomBytes(24).toString("hex"),
      nonce = randomBytes(24).toString("hex");
    const server = createServer();
    await new Promise<void>((ok, fail) => {
      server.once("error", fail);
      server.listen(0, "127.0.0.1", ok);
    });
    const address = server.address() as { port: number };
    const redirect = `http://127.0.0.1:${address.port}/callback`;
    try {
      const pending = new Promise<string>((ok, fail) => {
        const timer = setTimeout(
          () => fail(new Failure("auth_required", "Sign-in timed out")),
          120000,
        );
        server.on("request", (req, res) => {
          const u = new URL(req.url ?? "/", redirect);
          if (
            u.pathname !== "/callback" ||
            u.searchParams.get("state") !== state
          ) {
            res.writeHead(400).end("Invalid callback");
            return;
          }
          const code = u.searchParams.get("code");
          res
            .writeHead(200, { "Content-Type": "text/plain" })
            .end("Return to DistillPod.");
          clearTimeout(timer);
          code
            ? ok(code)
            : fail(new Failure("auth_required", "Sign-in cancelled"));
        });
      });
      const url = new URL("https://accounts.google.com/o/oauth2/v2/auth");
      url.search = new URLSearchParams({
        client_id: this.config.clientId!,
        redirect_uri: redirect,
        response_type: "code",
        scope: "openid email",
        state,
        nonce,
        code_challenge: createHash("sha256")
          .update(verifier)
          .digest("base64url"),
        code_challenge_method: "S256",
      }).toString();
      process.stderr.write(
        `Open this sign-in URL in your system browser (do not paste credentials into chat):\n${url}\n`,
      );
      const code = await pending;
      const provider = await post(
        "https://oauth2.googleapis.com/token",
        {
          client_id: this.config.clientId,
          client_secret: this.config.clientSecret ?? "",
          code,
          code_verifier: verifier,
          redirect_uri: redirect,
          grant_type: "authorization_code",
        },
        true,
      );
      const { payload } = await jwtVerify(
        provider.id_token,
        createRemoteJWKSet(
          new URL("https://www.googleapis.com/oauth2/v3/certs"),
        ),
        {
          audience: this.config.clientId,
          issuer: ["https://accounts.google.com", "accounts.google.com"],
        },
      );
      requireThat(
        payload.nonce === nonce,
        "auth_required",
        "OAuth nonce mismatch",
      );
      return this.save(
        await post(this.endpoint("accounts:signInWithIdp"), {
          postBody: new URLSearchParams({
            id_token: provider.id_token,
            providerId: "google.com",
          }).toString(),
          requestUri: redirect,
          returnSecureToken: true,
          autoCreate: false,
        }),
      );
    } finally {
      server.close();
    }
  }
}
