# Sentry ingestion and quota review

Review for [issue #27](https://github.com/andrepaim/distillpod/issues/27), November 8,
2026, against source `41b59f2`; integration rechecked against main `87f9fbb`. This is a read-only review: no Sentry settings,
client keys, billing, notifications, APKs or backend deployments were changed.

## Findings and verification status

Keep direct Sentry ingestion for the private alpha, retain the free Developer plan,
and treat the DSN as public. The principal remaining risk is forged reports
consuming capacity or polluting diagnostics, rather than access to stored reports.
A shared secret hidden in the APK or an unauthenticated proxy would not fix that.

The base subscription is **Developer**, but **a trial is active until November 21,
2026**. A feature visible today is not proof it remains available after that date.
Sentry explicitly states that spike protection does not apply during trials.
Logs are also outside the data categories covered by its documented spike protection.

| Item | Observed state | Consequence |
| --- | --- | --- |
| Base subscription | Developer; free; plan price 0; monthly billing interval | No paid upgrade is proposed |
| Trial | `onTrialPlan=true`, ends November 21 | Recheck effective entitlements and quotas after trial expiry |
| Base plan capacity | 5,000 errors and 5 GB of logs per month | Use these as planning baselines, not the expanded trial allowances |
| Current billing usage | 5 errors; 764,635 log bytes | Counts/bytes are different quota units; log item counts are not billed bytes |
| Overage fields | Base plan `allowOnDemand=false`; error/log on-demand budgets and spend are 0 | Current observations show no paid error/log capacity allocated or used |
| Other billing flags | `supportsOnDemand=true`, `onDemandDisabled=false`, `onDemandMaxSpend=null` | Do **not** describe this as a verified global zero-spend cap; confirm subscription controls before any future plan change |
| Observed projects | One, `distillpod-android` | Staging and production currently share a project and organization capacity |
| Client key configuration | One Default key; active; `rateLimit=null` | No per-key rate limit is configured; DSN/key values deliberately omitted |
| Project features reported by list endpoint | Includes rate limits and custom inbound filters during trial | This does not establish post-trial Developer entitlement or actual configured values |
| Issue alerts | Two production reliability alerts plus two defaults; no metric monitors returned | Existing failure alerts are not quota or log-volume alarms |
| Spike notification actions | Organization list returned no actions | No project spike notification action was configured at review time |
| Spike-protection setting | Project option `quotas:spike-protection-disabled=false` | Enabled as a setting, but ineffective during the active trial; not a logs protection |
| Filter lists | IP, release, error-message, log-message and metric-name lists are empty | No custom patterns or source-IP blocks configured in these lists |
| Built-in filters | `filtered-transaction` active; browser-extension, legacy-browser, localhost and web-crawler filters inactive; JavaScript hydration/chunk-load options enabled | These do not establish meaningful Android error/log flood protection |
| Spend notifications | Owner confirmed an 80% threshold in response to the settings review | Manual evidence; no notification email was induced |
| Spike setting / notification preference | Owner confirmed spike protection is on in response to the notification-settings check | Manual confirmation supplements the API toggle; no project action exists to trigger a spike notification |
| Automatic budget increases | No current paid error/log capacity in the Developer base plan; no such setting exposed by the inspected responses | Preserve zero budgets; verify any newly offered controls before a future subscription change |

The initial credential could inspect billing and aggregate outcomes but returned
403 for project settings. After the owner replaced it with a credential including
`project:read`, project details, filters and key configuration were read successfully.
The organization's separate spike endpoint remained inaccessible, but the project
option above provides the enabled-setting evidence. The credential cannot read the owner's personal notification settings (global user
endpoints returned 403). The owner supplied the remaining settings readout: spend
notifications are set to 80% and spike protection is on. This is manual evidence,
not an API read-back or proof of email delivery. No further credential scope is
required to finish this review. Actual changes remain outside this issue's scope.

## Observed ingestion

Read-only project outcomes covered the provider's seven-day window ending on
November 8 (roughly November 1–8; the last bucket can still receive data):

| Category | Accepted | Filtered | Rate-limited | Invalid / abuse / client-discard / cardinality-limited |
| --- | ---: | ---: | ---: | ---: |
| Errors | 5 | 0 | 0 | 0 |
| Log items | 1,405 | 0 | 0 | 0 |

Accepted-only environment aggregates matched these totals: production had 3 errors
and 914 log items; staging had 2 errors and 491 log items. Staging therefore accounts
for about 35% of accepted log items in this small sample. This is not evidence of
malicious traffic or a representative seven-day baseline: data arrived in only two
daily buckets. Nor does zero observed dropping prove that filters or limits are
configured. No individual error, log message, IP, installation identity, credential,
DSN, payment detail or personal event payload was included in the review.

[Usage outcomes](https://paim-3q.sentry.io/organizations/paim-3q/stats/),
[project settings](https://paim-3q.sentry.io/settings/projects/distillpod-android/),
[subscription](https://paim-3q.sentry.io/settings/billing/overview/), and
[spike protection](https://paim-3q.sentry.io/settings/spike-protection/) provide the
operator views. UI routes can redirect; select the same organization/project.

## Available protection and limits

- **Quota exhaustion:** with no paid capacity, excess ingestion is rejected/dropped;
  Sentry documents asynchronous enforcement and HTTP 429 responses. Quota is not
  an authentication boundary, and an attacker can deprive legitimate reports of
  capacity. Counts are not guaranteed hard instantaneous ceilings.
- **Per-key error limits:** documented for Business/Enterprise, not the ordinary
  Developer plan. They are not a documented log-byte or log-item cap. Trial feature
  flags do not change this post-trial constraint.
- **Spike protection:** applies to errors, spans/transactions and attachments;
  does not cover logs and is ineffective during trials. It adapts to sustained
  traffic, so it is not a fixed anti-abuse ceiling. Enabling it and enabling its
  notification action/personal delivery preferences are separate steps.
- **Inbound filters:** ordinary built-in error IP filtering can help with a known
  abusive source, subject to verified entitlement. The legacy IP filter does not
  apply to logs. Custom filters can cover logs/IPs, but require Trial,
  Business/Enterprise; legacy message/release lists are likewise plan-dependent.
  Event environments, release strings and synthetic flags are client-supplied and
  cannot authenticate the sender.
- **Usage notifications:** Sentry documents default spend notifications at 80%
  consumption and exhaustion; the owner confirmed the configured 80% threshold.
  Actual delivery was not tested. Issue alerts do not establish quota notifications
  or log-volume alarms, and an enabled personal spike preference cannot compensate
  for the missing project-level spike notification action.
- **Client controls:** app consent, redaction, the 5 MB / 1,000-file / 72-hour outbox,
  1,000-envelope daily budget and separate 30-error allowance reduce honest-client
  volume. Attackers can bypass all these by posting directly. `beforeSend` and
  sampling are similarly not server-side abuse controls.
- **API rate limiting:** management API request limits protect API endpoints; they
  are not a substitute for event-ingestion caps.

The app currently retains HTTP-429 envelopes and retries with backoff, capped by
outbox age/size. Sentry's error-quota guidance recommends dropping rate-limited
traffic rather than accumulating retries. This review records the difference; it
neither changes delivery behavior nor assumes that retries will recover expired
reports or exhausted monthly capacity.

## Recommended configuration and operating policy

These are proposals, not settings applied by this review.

1. Keep Developer and zero paid error/log budgets. Confirm that auto-increase or
   pay-as-you-go controls are disabled wherever offered; never interpret a null
   maximum or zero historical spend as a contractual hard cap. Recheck after trial
   expiry and before any subscription change. Accept losing telemetry at exhaustion
   instead of purchasing unapproved capacity.
2. Retain the verified active key and enabled spike setting; no key limit or custom
   filter is currently configured. Retain harmless built-ins; do not
   block mobile-carrier IPs, countries, releases or broad message patterns without
   identifying collateral loss. After trial expiry, recheck that error spike
   protection remains available/enabled and add an owner-only notification action
   with delivery enabled.
   This requires a separately authorized configuration change.
3. Review usage daily through trial expiry and weekly thereafter while volumes stay
   low. Investigate more than **50 errors/day**, more than **5 MB of logs/day**, or
   an order-of-magnitude increase against a subsequently measured comparable
   baseline. These are initial **investigation thresholds**, not configured alerts
   or provider caps. They are deliberately above this small sample and should be
   revised after at least seven populated days. Ensure provider 80% and exhaustion
   spend notifications reach the owner; the reported threshold is already 80%.
   No email or quota exhaustion was induced.
4. On a plan supporting per-key error caps, **100 errors/day** would cap one key at
   roughly 3,000/month, below the 5,000-error base quota and above current observed
   usage. This is a future option, **not available protection to promise on free
   Developer**, not a logs limit, and not a reservation for genuine reports.
5. Stop routine development traffic from using the production DSN. Prefer leaving
   ordinary debug/CI builds unconfigured, as today; route deliberate configured
   test builds to a separate staging project/key if ongoing tests justify it.
   Different environments alone do not isolate capacity. Separate projects improve
   attribution and revocation, but still share organization quotas; Developer does
   not thereby gain guaranteed capacity isolation. Do not create/rotate keys or
   change existing distributed builds as part of this review.
6. Keep direct ingestion for now: observed traffic is small and the organization is
   intended to stay free. Reconsider an authenticated gateway only for actual
   sustained abuse, a larger public rollout, or stronger availability requirements.
   It would need trusted enrollment/authentication, server-side size/schema and
   rate/budget limits, a fixed destination and revocation. An exposed legacy DSN
   would remain a bypass until revoked, breaking old clients' reporting. A gateway
   also adds operational costs and an endpoint that can itself be attacked.

## Flood and key-rotation runbook

1. **Confirm and bound the incident.** Record the UTC window and inspect Stats by
   project and separately by errors, log items and log bytes. Check accepted,
   filtered, rate-limited, invalid and abuse outcomes. Compare with deployment/test
   timing before attributing a spike to an attacker. Inspect aggregate metadata
   first; do not export personal event bodies into an issue or CI artifact.
2. **Protect cost and acknowledge visibility loss.** Verify Developer/current paid
   budgets in Subscription. Do not upgrade or enable overages. Missing events may
   be throttled or discarded; reports accepted by HTTP are not proof that every
   intended report was stored. Capture the gaps and provider reason codes.
3. **Use narrowly scoped controls when authorized.** Apply an available filter only
   after checking its data-category coverage and false-positive impact. Review
   dropped volume after the change. Error-only spike protection and legacy IP
   filters do not protect the log stream.
4. **Escalate a necessary key disablement.** Disabling Default immediately affects
   every installed build using it, including real reports queued for retry. Obtain
   explicit authorization for that disruption, record the time and affected builds,
   then deactivate the key in Client Keys. The current app retries 401/403, so
   local pending counts may persist until expiry; do not interpret that as success.
5. **Rotate with an update plan.** Create a replacement ingestion key when
   authorized, update the private build configuration and ship a separately
   authorized compatible APK. New app versions must consent/send normally and show
   successful acknowledgement. Leaving the old key active preserves compatibility
   but leaves the abuse route open. Revoking it stops reporting from old APKs; it
   does not delete existing stored reports or migrate old clients automatically.
   A public replacement key can eventually be extracted again.
6. **Verify recovery with bounded evidence.** Prefer settings read-back and outcomes.
   If a live check is needed, use at most one synthetic log and one handled error
   with consent, confirm acknowledgement and matching aggregate/provider evidence,
   and label their traffic. Never flood production, exhaust quota deliberately, or
   crash the owner phone. Recheck accepted/dropped volumes and document the final
   configuration and outstanding gaps.

## Evidence, validation and limits

Provider reads: organization/project list/details, customer subscription metadata,
seven-day stats summary, per-category dropped-event tools, aggregate environment
searches, client-key configuration, filter settings, alert inventory and spike
notification actions. Project details,
key settings and filters initially returned 403; these were verified after the
owner supplied project-read access. The separate organization spike endpoint still
returned 403; project settings provided the spike toggle. Personal spend/spike
notification endpoints returned 403; the owner supplied the 80% spend threshold
and on-state in response to the requested notification-settings readout. Effective
notification delivery was not tested.

No synthetic submissions were needed. No app behavior changed, so no Android build,
emulator or phone checks are claimed for this documentation increment. Validation is
source/document accuracy, local links, `git diff --check`, PR checks, and the applicable
combined-commit CI/security workflow. Project settings were read directly; the
remaining notification preferences were supplied by the owner. This completes the
review evidence without requiring live submissions. It does not claim that the
recommendations have been applied or that telemetry cannot be spoofed. Close #27
only after the review PR is merged and applicable combined-commit checks pass.

## Official references (checked November 8, 2026)

- [Billing quota management](https://docs.sentry.io/pricing/quotas/)
- [Manage error quota](https://docs.sentry.io/pricing/quotas/manage-event-stream-guide/)
- [Manage logs quota](https://docs.sentry.io/pricing/quotas/manage-logs-quota/)
- [Spike protection](https://docs.sentry.io/pricing/quotas/spike-protection/)
- [Inbound filtering and category coverage](https://docs.sentry.io/concepts/data-management/filtering/)
- [Spend notifications](https://docs.sentry.io/product/notifications/#spend-notifications)
- [List spike notification actions](https://docs.sentry.io/api/alerts/list-spike-protection-notifications/)
- [API rate limits](https://docs.sentry.io/api/ratelimits/)
- [Public DSN design](https://github.com/getsentry/sentry-docs/blob/master/docs/concepts/key-terms/dsn-explainer.mdx)
