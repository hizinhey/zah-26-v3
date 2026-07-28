# Web (Zalo Web) Platform Support Design

## Problem

OpsHub only supports the Android platform end to end. `OperationService`
hardcodes `!"ANDROID".equals(oa.platform())` as a rejection, `TestPlanService`
always generates the same five Android-named templates for every OA
regardless of platform, and the Local Hub's entire toolchain (`adb`, Appium,
WebdriverIO-against-a-mobile-device) assumes a physical Android device is
attached. The frontend's `Platform` type already includes `"WEB"` and `"PC"`
as selectable options, but choosing either is currently rejected at save
time.

## Goal

Let an operator create an Operation targeting the `WEB` platform — Zalo Web
(`chat.zalo.me`) opened in a desktop Chrome browser — generate the same five
kinds of test cases as Android, and execute them through a Local Hub-style
worker that drives Chrome via WebdriverIO instead of Appium/adb. `PC` (a
native desktop Zalo client, if that's ever a distinct target) and iOS remain
unsupported; this spec is Web only.

## Reference material

`config/`, `pages/`, `tests/`, `wdio.conf.ts`, `package.json` at the repo
root (commit `696e853`) are a raw WebdriverIO reference project covering
both Android (`tco_android_001-005`) and Zalo PC Web
(`tco_pc_web_006-010`), the same role `mobile_script/` played for the
original Android templates. This spec's templates are clean, parameterized
derivations of the `tco_pc_web_*` specs and `pages/*.PC_Web.ts` page objects
from that reference. Once the templates below exist, the raw reference
project moves out of the repo root — either deleted or relocated under an
ignored directory (matching the existing `mobile_script/` `.gitignore`
entry) — it should not remain a second, drifting copy of the same logic.

One caveat carried over from the reference: `hasUnreadBadge` in
`messages-tab.page.PC_Web.ts` uses an unconfirmed heuristic (`class contains
"badge"`) because no real unread conversation was available when it was
written. The ported template keeps this heuristic as-is; it should be
verified against a real unread OA conversation before being trusted, but
that verification is not a blocker for this spec.

## Backend

- `OperationService.validateOfficialAccounts`: accept `"WEB"` in addition to
  `"ANDROID"`; any other value (including `"PC"`, `"IOS"`) still throws
  `UnsupportedPlatformException`.
- All official accounts in one Operation must share the same platform value
  — no mixing Android and Web OAs in a single Operation. Validated at the
  same point as the existing per-OA platform check.
- `TestPlanService.createCases`: currently iterates the single `TemplateId`
  enum for every OA. It needs to select the template set by
  `account.getPlatform()` — a new template-id set for Web
  (`web-oa-delivery-v1`, `web-thumbnail-v1`, `web-content-v1`,
  `web-button-text-v1`, `web-redirect-v1`), parallel in structure to the
  existing `TemplateId` enum. `TestPlanService.TemplateParameters` is
  already platform-agnostic (`oaName`, `thumbnailUrl`, `expectedHeader`,
  `expectedBody`, `expectedButtonText`, `expectedRedirectUrl`,
  `expectedRedirectDomain`) and needs no field changes — the Web redirect
  template uses `expectedRedirectUrl` for a full origin+path+query-param
  compare (see Templates below), which the existing field already carries.
- `TemplateReadinessValidator`/`TemplateReadinessProperties`: currently one
  `templateRoot`/`catalogVersion` pair (default `local-hub/templates/android`,
  `android-v1`). Becomes platform-keyed: a second root/version pair for
  `local-hub/templates/web` (e.g. catalog version `web-v1`), selected by
  the template's platform when validating readiness.

## Local Hub: Web execution path

A second command/template path inside the existing `opshub_hub` package,
not a rewrite:

- **Template catalog**: `local-hub/templates/web/manifest.json` + five
  `.spec.ts.hbs` templates + page objects (`zalo-app.page.ts`,
  `bottom-tab-bar.page.ts`, `messages-tab.page.ts`,
  `zbusiness-chat.page.ts`), parameterized the same way Android's are
  (`{{{json name}}}` placeholders, verified through `TemplateCatalog`'s
  existing checksum/substitution logic — no changes needed to
  `templates.py` itself).
- **Execution**: a `command_builder` that runs
  `npx wdio run wdio.web.conf.ts --spec <path>` against a WebdriverIO
  config using a plain `browserName: chrome` capability. WebdriverIO v9
  manages its own matching chromedriver — **no Appium, no adb, no mobile
  device** for this path.
- **Login**: a persistent Chrome profile directory (`--user-data-dir`,
  provisioned once per worker host) holding a one-time manual QR-code login
  for a dedicated test account, exactly as in the reference project's
  `wdio.pc_web.conf.ts`. Not Playwright `storageState` — this is a real
  Chrome profile on disk, reused across runs.
- **Preflight**: a Web-specific preflight profile replaces the
  adb/Appium/Zalo-package checks with: `node` present, Chrome installed,
  and the configured profile directory exists (i.e. someone has already
  done the one-time QR login). The existing `template-manifest-checksum`
  and `writable:data-root` checks apply unchanged, pointed at the Web
  catalog/work dir.
- **Concurrency**: sequential only, same as the reference
  (`maxInstances: 1`) — the shared Chrome profile can't be opened by two
  Chrome instances at once. One Web execution at a time per worker/profile.
- **Reuse**: `Runner`'s outbox enqueue/flush, progress/result envelope
  reporting, evidence capture-and-upload, and infrastructure-vs-assertion
  failure classification are platform-agnostic already and apply to the
  Web path unchanged. `screenshot_capturer` becomes a WebdriverIO
  `browser.saveScreenshot()` call instead of `adb exec-out screencap`;
  there is no Appium session to reset between retry attempts on this path
  (`reset_appium_session` stays `None` for Web jobs).

## Trigger and worker lifecycle

Per-run, on demand — not an always-on polling Hub like Android's:

- When an operator starts an execution for a `WEB`-platform Operation, the
  backend spawns the Web worker as a subprocess directly (no new sidecar
  service). The worker is a normal Local Hub process from the backend's
  point of view — it registers, heartbeats, and receives its job through
  the existing Hub WebSocket/polling protocol and `X-Hub-Token`
  authentication, the same contract the Android Hub already uses.
  `ExecutionService.start` already requires an online Hub
  (`HubNotOnlineException` otherwise); the backend waits for the freshly
  spawned worker's first heartbeat before proceeding, rather than needing a
  new dispatch path.
- The worker process exits once its one job completes (pass, fail, or
  error) rather than continuing to poll indefinitely.
- If the operator starts a second Web execution while one is still running,
  it's rejected the same way a second execution against a busy/leased Hub
  is rejected today — there's no concurrent-worker spawning in this spec.

## Templates (5, mirroring Android's structure)

1. **OA delivery + unread badge** — open `chat.zalo.me`, dismiss the
   "don't sync messages" prompt if present, assert the OA conversation is
   visible in the main list with an unread indicator.
2. **Thumbnail** — assert the last rich-message card's thumbnail matches
   the expected image URL. Read directly from the CSS `background-image`
   value and compare strings (case-insensitive); no pixel diffing, no
   `sharp`/`pixelmatch` dependency needed for this platform (unlike
   Android's screenshot-based comparison).
3. **Content** — assert the last card's header and body text match
   exactly.
4. **Button text** — assert the last card's CTA button text matches
   exactly.
5. **Redirect** — click the CTA, wait for a new browser tab to open,
   switch to it, and compare origin + path + every query param present in
   the expected URL, ignoring extra params Zalo injects itself (e.g.
   `gidzl`). This is a fuller comparison than Android's domain-only check,
   made possible because Web's CTA opens a real new tab rather than an
   in-app WebView.

## Out of scope

- Native PC desktop Zalo client (the `PC` platform value stays rejected).
- iOS.
- Mixing platforms within one Operation.
- Concurrent Web executions / multiple Web workers.
- Automatically capturing or refreshing the Chrome login profile — the
  one-time QR login remains a manual operator step.
- Fixing/reverifying the `hasUnreadBadge` heuristic beyond what the
  reference already implemented (noted above as a follow-up, not a
  blocker).
