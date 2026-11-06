# DistillPod voice credential broker

This Node 22 service verifies a Firebase ID token, restricts access to one owner UID and obtains a short-lived OpenAI Realtime client credential. It never accepts a model, upstream URL, transcript or recording from clients. The OpenAI project key remains server-side. Use an API organization with the owner's claimed Pro credits.

## Local checks

```sh
npm ci --omit=optional --ignore-scripts
npm test
```

Policy tests use injected identity/provider functions and require no credentials. Android's fake-WebSocket tests also need no live account.

## Deployment configuration

Prepare a Cloud Run service in the Firebase project's Google Cloud project, with application-default credentials able to verify Firebase Auth users/revocation. Supply:

- `VOICE_OWNER_UID`: the Firebase UID permitted to use voice.
- `OPENAI_API_KEY`: a project key mounted through Secret Manager, never a committed file or command-line argument.
- `VOICE_ENABLED=true`: explicit enable switch; other values disable issuance.
- `PORT`: supplied by Cloud Run.

Build using the Dockerfile. Restrict the alpha to one maximum instance; use minimum instances zero and finite request timeouts. HTTPS ingress may be public because application-level Firebase authorization protects `/session`. Configure resource/billing limits and alerts before deploying. Actual deployment and billing activation are separate operations; no deployment command runs as part of building the APK.

`POST /session` accepts only `{}` and `Authorization: Bearer <Firebase ID token>`. Success returns `{value, expires_at}` with `Cache-Control: no-store`. The app uses that ephemeral token directly with OpenAI. Replies and failures never include the project key or upstream response body.

Limits: 100 issuance attempts per UTC day and at least ten seconds between attempts, with one in-flight request. These limits are in memory and reset on process restart; multiple instances each have their own counter. They are alpha abuse controls, not durable billing enforcement. Tokens expire for new connections after 30 seconds, may establish multiple sessions before expiry, and established sessions may outlive token expiry. OpenAI also permits client session configuration overrides. Neither the minting defaults nor APK timeouts create a hard monetary cap. A distributed service needs a relay or equivalent trusted session enforcement and durable accounting.

The server logs no identities, bearer tokens, transcript text or provider error bodies. Platform request logging should likewise exclude authorization headers and bodies. Existing ChatGPT OAuth credentials are never forwarded to this service. Owner credit exhaustion returns a sanitized provider-unavailable response; no automatic fallback billing provider is configured.

The container omits optional Firestore/Storage clients because this service uses Firebase Auth only. Audit the deployed dependency set with `npm audit --omit=optional --omit=dev`.
