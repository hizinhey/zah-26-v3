# Web (Zalo Web) Platform Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an operator create an Operation targeting the `WEB` platform (Zalo Web at `chat.zalo.me`, driven by desktop Chrome), generate the same five kinds of test cases Android has, and execute them through a second Local Hub execution path (WebdriverIO + Chrome, no Appium/adb) that the backend spawns on demand when a Web execution starts.

**Architecture:** Extends the existing single-platform (Android) pipeline into a platform-keyed one at every layer it currently hardcodes `"ANDROID"`: the wire contracts (OpenAPI + JSON Schema), backend platform validation, template selection/readiness/generation, the Hub job envelope, and the Local Hub's own payload model. Adds a parallel Web template catalog and Web execution path (preflight, screenshot capture, command builder) inside the existing `opshub_hub` package, and a small backend component that spawns the Web worker process on demand.

**Tech Stack:** Java/Spring Boot (backend), Python/Pydantic (Local Hub), TypeScript/WebdriverIO (generated test specs), JSON Schema + OpenAPI (contracts).

## Global Constraints

- One platform per Operation — no mixing Android and Web official accounts in a single Operation (per `docs/superpowers/specs/2026-07-28-web-platform-support-design.md`).
- Web execution is sequential only (one Web execution at a time); no concurrent Web workers in this plan.
- Web login is a persistent Chrome `--user-data-dir` profile provisioned once via manual QR scan — not captured, refreshed, or automated by this plan.
- Read any `Instant`/`timestamptz` column via `rs.getTimestamp(...).toInstant()`, never `rs.getObject(col, Instant.class)` — pgjdbc does not support the latter for `timestamptz` (see `backend/src/main/java/com/opshub/execution/application/ExecutionService.java:53`, fixed in commit `0d37f3f`).
- Native PC desktop client and iOS remain unsupported (`UnsupportedPlatformException` still rejects everything except `ANDROID`/`WEB`).

---

### Task 1: Contracts — accept `WEB` in the OpenAPI and Hub-envelope schemas

**Files:**
- Modify: `contracts/openapi/opshub-v1.yaml`
- Modify: `contracts/schemas/hub-envelope-v1.json`
- Modify: `contracts/tests/test_contract_examples.py`

**Interfaces:**
- Produces: `OfficialAccountInput.platform` and `OfficialAccount.platform` accept `ANDROID` or `WEB` (OpenAPI).
- Produces: `JobOfferedPayload.platform` accepts `ANDROID` or `WEB`; `TestCase.templateId` enum includes the 5 new `web-*-v1` ids alongside the 5 `android-*-v1` ids (JSON Schema).

- [x] **Step 1: Update the OpenAPI schema**

In `contracts/openapi/opshub-v1.yaml`, change both occurrences of `platform: { const: ANDROID }` (lines 215 and 244, inside `OfficialAccountInput` and `OfficialAccount`) to:

```yaml
        platform: { enum: [ANDROID, WEB] }
```

- [x] **Step 2: Update the Hub-envelope JSON Schema**

In `contracts/schemas/hub-envelope-v1.json`, change the `TestCase.templateId` property (around line 83-92):

```json
        "templateId": {
          "type": "string",
          "enum": [
            "android-oa-delivery-v1",
            "android-thumbnail-v1",
            "android-content-v1",
            "android-button-text-v1",
            "android-redirect-v1",
            "web-oa-delivery-v1",
            "web-thumbnail-v1",
            "web-content-v1",
            "web-button-text-v1",
            "web-redirect-v1"
          ]
        },
```

Change `JobOfferedPayload.platform` (around line 111):

```json
        "platform": { "enum": ["ANDROID", "WEB"] },
```

- [x] **Step 3: Update the contract example test's schema assertion**

In `contracts/tests/test_contract_examples.py`, line 197 currently asserts:

```python
        assert schemas["OfficialAccountInput"]["properties"]["platform"] == {"const": "ANDROID"}
```

Change it to:

```python
        assert schemas["OfficialAccountInput"]["properties"]["platform"] == {"enum": ["ANDROID", "WEB"]}
```

- [x] **Step 4: Run the contract tests**

```bash
python -m pytest contracts/tests -q
```

Expected: all pass (the existing Android-only fixtures at the top of the file, lines 13-26, are still valid — `"ANDROID"` remains an accepted value).

- [x] **Step 5: Commit**

```bash
git add contracts/openapi/opshub-v1.yaml contracts/schemas/hub-envelope-v1.json contracts/tests/test_contract_examples.py
git commit -m "feat: accept WEB platform in OpenAPI and Hub-envelope contracts"
```

---

### Task 2: Backend — allow `WEB` official accounts, one platform per Operation

**Files:**
- Modify: `backend/src/main/java/com/opshub/operation/application/OperationService.java`
- Modify: `backend/src/main/java/com/opshub/operation/application/UnsupportedPlatformException.java`
- Test: `backend/src/test/java/com/opshub/operation/OperationServiceTest.java`

**Interfaces:**
- Produces: `OperationService.replaceOas` accepts `platform = "WEB"` official accounts; throws `IllegalArgumentException` if an Operation's OAs mix platforms; still throws `UnsupportedPlatformException` for anything other than `ANDROID`/`WEB`.

- [x] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/opshub/operation/OperationServiceTest.java`, after `rejectsNonAndroidOfficialAccounts` (after line 60):

```java
    @Test
    void acceptsWebOfficialAccounts() {
        Operation operation = service.create("MOB-127");

        Operation updated = service.replaceOas(operation.getId(), 1, List.of(
                oa("WEB", "Web account")
        ));

        assertThat(updated.getOfficialAccounts()).extracting(account -> account.getPlatform())
                .containsExactly("WEB");
    }

    @Test
    void rejectsMixedPlatformsWithinOneOperation() {
        Operation operation = service.create("MOB-128");

        assertThatThrownBy(() -> service.replaceOas(operation.getId(), 1, List.of(
                oa("ANDROID", "Android account"),
                oa("WEB", "Web account")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same platform");
    }
```

- [x] **Step 2: Run the tests to verify they fail**

```bash
sudo ./mvnw -pl backend -am -Dtest=OperationServiceTest test
```

Expected: `acceptsWebOfficialAccounts` fails with `UnsupportedPlatformException`; `rejectsMixedPlatformsWithinOneOperation` fails because no such rejection exists yet.

- [x] **Step 3: Update `UnsupportedPlatformException`'s message**

In `backend/src/main/java/com/opshub/operation/application/UnsupportedPlatformException.java`:

```java
package com.opshub.operation.application;

public class UnsupportedPlatformException extends RuntimeException {
    public UnsupportedPlatformException(String platform) {
        super("Only ANDROID or WEB official accounts are supported, got: " + platform);
    }
}
```

- [x] **Step 4: Update `OperationService.validateOfficialAccounts`**

In `backend/src/main/java/com/opshub/operation/application/OperationService.java`, replace the `validateOfficialAccounts` method (lines 83-99):

```java
    private void validateOfficialAccounts(List<SaveOaCommand> oas) {
        if (oas == null) {
            throw new IllegalArgumentException("oas must not be null");
        }
        String platform = null;
        for (SaveOaCommand oa : oas) {
            if (oa == null) {
                throw new IllegalArgumentException("oa must not be null");
            }
            if (!"ANDROID".equals(oa.platform()) && !"WEB".equals(oa.platform())) {
                throw new UnsupportedPlatformException(oa.platform());
            }
            if (platform == null) {
                platform = oa.platform();
            } else if (!platform.equals(oa.platform())) {
                throw new IllegalArgumentException("All official accounts in one Operation must share the same platform");
            }
            if (isBlank(oa.oaName()) || isBlank(oa.thumbnailUrl()) || isBlank(oa.content())
                    || isBlank(oa.buttonText()) || isBlank(oa.redirectUrl())) {
                throw new IllegalArgumentException("All official account fields must be provided");
            }
        }
    }
```

- [x] **Step 5: Run the tests to verify they pass**

```bash
sudo ./mvnw -pl backend -am -Dtest=OperationServiceTest test
```

Expected: all pass, including the pre-existing `rejectsNonAndroidOfficialAccounts` (still rejects `"IOS"`).

- [x] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/operation/application/OperationService.java \
        backend/src/main/java/com/opshub/operation/application/UnsupportedPlatformException.java \
        backend/src/test/java/com/opshub/operation/OperationServiceTest.java
git commit -m "feat: accept WEB official accounts, one platform per Operation"
```

---

### Task 3: Local Hub — Web template catalog

**Files:**
- Create: `local-hub/templates/web/manifest.json`
- Create: `local-hub/templates/web/pages/zalo-app.page.ts`
- Create: `local-hub/templates/web/pages/bottom-tab-bar.page.ts`
- Create: `local-hub/templates/web/pages/messages-tab.page.ts`
- Create: `local-hub/templates/web/pages/zbusiness-chat.page.ts`
- Create: `local-hub/templates/web/tests/web-oa-delivery-v1.spec.ts.hbs`
- Create: `local-hub/templates/web/tests/web-thumbnail-v1.spec.ts.hbs`
- Create: `local-hub/templates/web/tests/web-content-v1.spec.ts.hbs`
- Create: `local-hub/templates/web/tests/web-button-text-v1.spec.ts.hbs`
- Create: `local-hub/templates/web/tests/web-redirect-v1.spec.ts.hbs`
- Test: `local-hub/tests/templates/test_web_template_catalog.py`

Derived from the reference project committed in `696e853` (`pages/*.PC_Web.ts`, `tests/tco_pc_web_006-010.spec.ts`), parameterized the same way `local-hub/templates/android/` is.

**Interfaces:**
- Produces: `local-hub/templates/web/manifest.json` with `catalogVersion: "web-v1"` and 5 template entries, each with a real `sha256` computed in Step 2 below.

- [x] **Step 1: Create the page objects**

Create `local-hub/templates/web/pages/zalo-app.page.ts`:

```typescript
const ZALO_WEB_URL = 'https://chat.zalo.me/';

/**
 * Zalo Web login only happens by scanning a QR code with a phone that already has Zalo
 * signed in - it cannot be automated. Chrome uses a fixed profile (--user-data-dir,
 * configured in wdio.web.conf.ts) so the QR scan only has to happen once; later runs
 * start already signed in.
 */
class ZaloWebApp {
  async open(): Promise<void> {
    await browser.url(ZALO_WEB_URL);
    await this._dismissSyncPromptIfPresent();
  }

  async isOpened(): Promise<boolean> {
    return (await browser.getUrl()).includes('chat.zalo.me');
  }

  private async _dismissSyncPromptIfPresent(): Promise<void> {
    const dismissButton = $('button*=Tôi không muốn đồng bộ');
    if (await dismissButton.isExisting()) {
      await dismissButton.click();
    }
  }
}

export default new ZaloWebApp();
```

Create `local-hub/templates/web/pages/bottom-tab-bar.page.ts`:

```typescript
/**
 * Zalo Web's main navigation is a left sidebar, not a bottom bar - this file keeps the
 * same name/shape as bottom-tab-bar.page.ts on the Android side for cross-platform
 * consistency. The Messages tab is selected by default when chat.zalo.me opens.
 */
class LeftNavBar {
  get messagesTab() {
    return $('div[data-id="div_Main_TabMsg"]');
  }

  get contactsTab() {
    return $('div[data-translate-title="STR_TAB_CONTACT"]');
  }

  async openMessagesTab(): Promise<void> {
    await this.messagesTab.click();
  }

  async openContactsTab(): Promise<void> {
    await this.contactsTab.click();
  }
}

export default new LeftNavBar();
```

Create `local-hub/templates/web/pages/messages-tab.page.ts`:

```typescript
class MessagesTab {
  get searchInput() {
    return $('#contact-search-input');
  }

  searchResultByName(name: string) {
    return $(
      `//div[contains(@class,"conv-item")][.//span[@class="txt-highlight" and text()="${name}"]]`
    );
  }

  conversationInMainListByName(name: string) {
    return $(
      `//div[contains(@class,"conv-item")]` +
        `[.//*[contains(@class,"conv-item-title__name")]//*[normalize-space(text())="${name}"]]`
    );
  }

  async searchConversation(name: string): Promise<void> {
    await this.searchInput.click();
    await this.searchInput.setValue(name);
    await this.searchResultByName(name).waitForDisplayed();
  }

  async openConversation(name: string): Promise<void> {
    if (await this.isConversationDisplayed(name)) {
      await this.conversationInMainListByName(name).click();
      return;
    }
    await this.searchConversation(name);
    await this.searchResultByName(name).click();
  }

  async isConversationDisplayed(name: string): Promise<boolean> {
    try {
      return await this.conversationInMainListByName(name).isDisplayed();
    } catch {
      return false;
    }
  }

  /**
   * Unconfirmed heuristic, carried over from the reference project: no real unread
   * conversation was available when this was written, so there's no confirmed example
   * to derive the exact badge class name from. Verify against a real unread OA
   * conversation before trusting this assertion (see the design spec's caveat).
   */
  async hasUnreadBadge(name: string): Promise<boolean> {
    const badge = this.conversationInMainListByName(name).$('.//*[contains(@class,"badge")]');
    return badge.isExisting();
  }
}

export default new MessagesTab();
```

Create `local-hub/templates/web/pages/zbusiness-chat.page.ts`:

```typescript
const RICH_CARD_SELECTOR = 'div[contains(@class,"card--oa")]';
const CARD_THUMBNAIL_CLASS = 'oa-msg-header__img';
const CARD_HEADER_CLASS = 'oa-msg-header__title';
const CARD_BODY_CLASS = 'oa-msg-header__desc';
const CARD_BUTTON_ROW_CLASS = 'oa-msg-child';
const CARD_BUTTON_TEXT_CLASS = 'oa-msg-child__title';

class ZBusinessChatWeb {
  get lastRichCard() {
    return $(`(//${RICH_CARD_SELECTOR})[last()]`);
  }

  async waitForOpened(): Promise<void> {
    await this.lastRichCard.waitForDisplayed();
  }

  get lastCardThumbnail() {
    return this.lastRichCard.$(`.${CARD_THUMBNAIL_CLASS}`);
  }

  get lastCardHeader() {
    return this.lastRichCard.$(`.${CARD_HEADER_CLASS}`);
  }

  get lastCardBody() {
    return this.lastRichCard.$(`.${CARD_BODY_CLASS}`);
  }

  get lastCardButtonRow() {
    return this.lastRichCard.$(`.${CARD_BUTTON_ROW_CLASS}`);
  }

  get lastCardButtonText() {
    return this.lastRichCard.$(`.${CARD_BUTTON_TEXT_CLASS}`);
  }

  async checkThumbnailUrl(expectedImageUrl: string): Promise<void> {
    const actualUrl = await this._getThumbnailUrl();
    expect(actualUrl?.toLowerCase() ?? null).toBe(expectedImageUrl.toLowerCase());
  }

  private async _getThumbnailUrl(): Promise<string | null> {
    const backgroundImage = await this.lastCardThumbnail.getCSSProperty('background-image');
    const match = String(backgroundImage.value).match(/url\(["']?(.*?)["']?\)/);
    return match ? match[1] : null;
  }

  async checkHeaderText(expectedHeader: string): Promise<void> {
    expect(await this.lastCardHeader.getText()).toBe(expectedHeader);
  }

  async checkBodyText(expectedBody: string): Promise<void> {
    expect(await this.lastCardBody.getText()).toBe(expectedBody);
  }

  async checkButtonText(expectedText: string): Promise<void> {
    expect((await this.lastCardButtonText.getText()).trim()).toBe(expectedText);
  }

  private _windowHandlesBeforeClick: string[] = [];

  async tapLastCardButton(): Promise<void> {
    this._windowHandlesBeforeClick = await browser.getWindowHandles();
    await this.lastCardButtonRow.click();
  }

  async checkRedirectUrl(expectedUrl: string): Promise<void> {
    await browser.waitUntil(
      async () => (await browser.getWindowHandles()).length > this._windowHandlesBeforeClick.length,
      { timeout: 10000, timeoutMsg: 'No new browser tab opened after tapping the CTA button' }
    );
    const handlesAfter = await browser.getWindowHandles();
    const newHandle = handlesAfter.find((handle) => !this._windowHandlesBeforeClick.includes(handle));
    await browser.switchToWindow(newHandle as string);

    await browser
      .waitUntil(async () => (await browser.getUrl()).includes('utm_source'), { timeout: 8000 })
      .catch(() => undefined);

    const actual = new URL(await browser.getUrl());
    const expected = new URL(expectedUrl);
    expect(actual.origin + actual.pathname).toBe(expected.origin + expected.pathname);
    for (const [key, value] of expected.searchParams) {
      expect(actual.searchParams.get(key)).toBe(value);
    }
  }
}

export default new ZBusinessChatWeb();
```

- [x] **Step 2: Create the five spec templates**

Create `local-hub/templates/web/tests/web-oa-delivery-v1.spec.ts.hbs`:

```typescript
import zaloApp from '../pages/zalo-app.page';
import bottomTabBar from '../pages/bottom-tab-bar.page';
import messagesTab from '../pages/messages-tab.page';

const test = it;
const OA_NAME = {{{json oaName}}};

describe('@web OA delivery', () => {
  beforeEach(async () => {
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();
  });

  test('shows the delivered official account with an unread badge', async () => {
    expect(await zaloApp.isOpened()).toBe(true);
    expect(await messagesTab.isConversationDisplayed(OA_NAME)).toBe(true);
    expect(await messagesTab.hasUnreadBadge(OA_NAME)).toBe(true);
  });
});
```

Create `local-hub/templates/web/tests/web-thumbnail-v1.spec.ts.hbs`:

```typescript
import zaloApp from '../pages/zalo-app.page';
import bottomTabBar from '../pages/bottom-tab-bar.page';
import messagesTab from '../pages/messages-tab.page';
import zBusinessChat from '../pages/zbusiness-chat.page';

const test = it;
const OA_NAME = {{{json oaName}}};
const EXPECTED_THUMBNAIL_URL = {{{json thumbnailUrl}}};

describe('@web OA thumbnail', () => {
  beforeEach(async () => {
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('shows the expected thumbnail on the latest card', async () => {
    await zBusinessChat.checkThumbnailUrl(EXPECTED_THUMBNAIL_URL);
  });
});
```

Create `local-hub/templates/web/tests/web-content-v1.spec.ts.hbs`:

```typescript
import zaloApp from '../pages/zalo-app.page';
import bottomTabBar from '../pages/bottom-tab-bar.page';
import messagesTab from '../pages/messages-tab.page';
import zBusinessChat from '../pages/zbusiness-chat.page';

const test = it;
const OA_NAME = {{{json oaName}}};
const EXPECTED_HEADER = {{{json expectedHeader}}};
const EXPECTED_BODY = {{{json expectedBody}}};

describe('@web OA content', () => {
  beforeEach(async () => {
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('shows the expected header and body on the latest card', async () => {
    await zBusinessChat.checkHeaderText(EXPECTED_HEADER);
    await zBusinessChat.checkBodyText(EXPECTED_BODY);
  });
});
```

Create `local-hub/templates/web/tests/web-button-text-v1.spec.ts.hbs`:

```typescript
import zaloApp from '../pages/zalo-app.page';
import bottomTabBar from '../pages/bottom-tab-bar.page';
import messagesTab from '../pages/messages-tab.page';
import zBusinessChat from '../pages/zbusiness-chat.page';

const test = it;
const OA_NAME = {{{json oaName}}};
const EXPECTED_BUTTON_TEXT = {{{json expectedButtonText}}};

describe('@web OA button text', () => {
  beforeEach(async () => {
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('shows the expected button text on the latest card', async () => {
    await zBusinessChat.checkButtonText(EXPECTED_BUTTON_TEXT);
  });
});
```

Create `local-hub/templates/web/tests/web-redirect-v1.spec.ts.hbs`:

```typescript
import zaloApp from '../pages/zalo-app.page';
import bottomTabBar from '../pages/bottom-tab-bar.page';
import messagesTab from '../pages/messages-tab.page';
import zBusinessChat from '../pages/zbusiness-chat.page';

const test = it;
const OA_NAME = {{{json oaName}}};
const EXPECTED_REDIRECT_URL = {{{json expectedRedirectUrl}}};

describe('@web OA redirect', () => {
  beforeEach(async () => {
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('opens the configured redirect URL from the latest card', async () => {
    await zBusinessChat.tapLastCardButton();
    await zBusinessChat.checkRedirectUrl(EXPECTED_REDIRECT_URL);
  });
});
```

- [x] **Step 3: Compute each template's checksum**

```bash
cd local-hub/templates/web
for f in tests/web-oa-delivery-v1.spec.ts.hbs tests/web-thumbnail-v1.spec.ts.hbs tests/web-content-v1.spec.ts.hbs tests/web-button-text-v1.spec.ts.hbs tests/web-redirect-v1.spec.ts.hbs; do
  printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
done
cd -
```

Record the five printed hashes — they go into `manifest.json` in Step 4 and into `WebTemplateId` in Task 4.

- [x] **Step 4: Create the manifest**

Create `local-hub/templates/web/manifest.json`, substituting `<sha256-oa-delivery>` etc. with the exact hashes computed in Step 3 (do not reuse the placeholder text below — those are not real hashes):

```json
{
  "catalogVersion": "web-v1",
  "templates": [
    {
      "id": "web-oa-delivery-v1",
      "version": 1,
      "path": "tests/web-oa-delivery-v1.spec.ts.hbs",
      "sha256": "<sha256-oa-delivery>",
      "parameterSchema": "template-parameters-v1"
    },
    {
      "id": "web-thumbnail-v1",
      "version": 1,
      "path": "tests/web-thumbnail-v1.spec.ts.hbs",
      "sha256": "<sha256-thumbnail>",
      "parameterSchema": "template-parameters-v1"
    },
    {
      "id": "web-content-v1",
      "version": 1,
      "path": "tests/web-content-v1.spec.ts.hbs",
      "sha256": "<sha256-content>",
      "parameterSchema": "template-parameters-v1"
    },
    {
      "id": "web-button-text-v1",
      "version": 1,
      "path": "tests/web-button-text-v1.spec.ts.hbs",
      "sha256": "<sha256-button-text>",
      "parameterSchema": "template-parameters-v1"
    },
    {
      "id": "web-redirect-v1",
      "version": 1,
      "path": "tests/web-redirect-v1.spec.ts.hbs",
      "sha256": "<sha256-redirect>",
      "parameterSchema": "template-parameters-v1"
    }
  ]
}
```

- [x] **Step 5: Write a catalog-loading test**

Create `local-hub/tests/templates/test_web_template_catalog.py` (mirrors `local-hub/tests/templates/test_template_catalog.py`'s structure for the Android catalog — read that file first for the exact assertions it makes, then write the equivalent against the Web catalog):

```python
from pathlib import Path

from opshub_hub.models import TemplateParametersV1
from opshub_hub.templates import TemplateCatalog

TEMPLATE_ROOT = Path(__file__).resolve().parents[2] / "templates" / "web"

SAMPLE_PARAMETERS = TemplateParametersV1(
    oaName="zBusiness",
    thumbnailUrl="https://res-zalo.zadn.vn/upload/media/2025/9/16/thumb.png",
    expectedHeader="Header",
    expectedBody="Body",
    expectedButtonText="Nâng cấp ngay",
    expectedRedirectUrl="https://business.zbox.vn/nang-cap-business-lite?value_type=2",
    expectedRedirectDomain="business.zbox.vn",
)


def test_manifest_declares_five_web_templates():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    assert catalog.catalog_version == "web-v1"
    for template_id in (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ):
        assert catalog.entry(template_id).id == template_id


def test_catalog_verifies_checksums():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    catalog.verify()


def test_every_template_renders_with_no_leftover_placeholders():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    values = SAMPLE_PARAMETERS.model_dump()
    for template_id in (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ):
        rendered = catalog.render(template_id, values)
        assert "{{" not in rendered
        assert "zBusiness" in rendered
```

- [x] **Step 6: Run the test to verify it passes**

```bash
cd local-hub && python -m pytest tests/templates/test_web_template_catalog.py -v
```

Expected: `3 passed`. If `test_catalog_verifies_checksums` fails, the hashes pasted into `manifest.json` in Step 4 don't match the actual file contents — recompute with Step 3's command and fix `manifest.json`.

- [x] **Step 7: Commit**

```bash
git add local-hub/templates/web local-hub/tests/templates/test_web_template_catalog.py
git commit -m "feat: add Web template catalog (5 templates, page objects, manifest)"
```

- [x] **Step 8: Retire the raw reference dump**

The clean, parameterized templates created above are now the tracked source of truth; the raw reference project committed in `696e853` (`config/`, `pages/`, `tests/`, `wdio.conf.ts`, `package.json`, `package-lock.json`, `tsconfig.json` at the repo root) is a second, drifting copy of the same logic and should not remain there, per the design spec's "Reference material" section:

```bash
git rm -r config pages tests wdio.conf.ts package.json package-lock.json tsconfig.json
```

- [x] **Step 9: Commit the removal**

```bash
git commit -m "chore: remove raw PC Web/Android reference project, superseded by local-hub/templates/"
```

---

### Task 4: Backend — `TemplateDescriptor` interface and `WebTemplateId`

**Files:**
- Create: `backend/src/main/java/com/opshub/generation/domain/TemplateDescriptor.java`
- Modify: `backend/src/main/java/com/opshub/generation/domain/TemplateId.java`
- Create: `backend/src/main/java/com/opshub/generation/domain/WebTemplateId.java`

**Interfaces:**
- Produces: `TemplateDescriptor { String id(); int version(); String sha256(); String platform(); }`
- Produces: `TemplateId implements TemplateDescriptor` (platform `"ANDROID"`, unchanged ids/versions/hashes).
- Produces: `WebTemplateId implements TemplateDescriptor` (platform `"WEB"`), 5 constants using the checksums computed in Task 3 Step 3.

- [x] **Step 1: Create `TemplateDescriptor`**

Create `backend/src/main/java/com/opshub/generation/domain/TemplateDescriptor.java`:

```java
package com.opshub.generation.domain;

public interface TemplateDescriptor {
    String id();

    int version();

    String sha256();

    String platform();
}
```

- [x] **Step 2: Retrofit `TemplateId`**

In `backend/src/main/java/com/opshub/generation/domain/TemplateId.java`, change the declaration and add the `platform()` method:

```java
package com.opshub.generation.domain;

public enum TemplateId implements TemplateDescriptor {
    OA_DELIVERY("android-oa-delivery-v1", 1, "578a8074cc9c58c27565e70dc798fa815d940632cdee8140b58d2b18a8919132"),
    THUMBNAIL("android-thumbnail-v1", 1, "84d686d049ccd8def4d3bcb986e51ca6f66ca89ba0cc48fefe7bbf8f515ab079"),
    CONTENT("android-content-v1", 1, "e2532e5f248e804748b05a3c2995ea70e80465262b57d793936ec1861a6f53a5"),
    BUTTON_TEXT("android-button-text-v1", 1, "3a50d4b798c13f5c383d97281911471aa486155ed1e8cb7aa98b396a215c60b7"),
    REDIRECT("android-redirect-v1", 1, "ff72c8998ca04de21d7a2392ed26842adf64653946bdc194b99f16c6e3d0852d");

    private final String id;
    private final int version;
    private final String sha256;

    TemplateId(String id, int version, String sha256) {
        this.id = id;
        this.version = version;
        this.sha256 = sha256;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public String platform() {
        return "ANDROID";
    }
}
```

- [x] **Step 3: Create `WebTemplateId`**

Create `backend/src/main/java/com/opshub/generation/domain/WebTemplateId.java`, substituting the same five hashes computed in Task 3 Step 3 (must byte-for-byte match `local-hub/templates/web/manifest.json`):

```java
package com.opshub.generation.domain;

public enum WebTemplateId implements TemplateDescriptor {
    OA_DELIVERY("web-oa-delivery-v1", 1, "<sha256-oa-delivery>"),
    THUMBNAIL("web-thumbnail-v1", 1, "<sha256-thumbnail>"),
    CONTENT("web-content-v1", 1, "<sha256-content>"),
    BUTTON_TEXT("web-button-text-v1", 1, "<sha256-button-text>"),
    REDIRECT("web-redirect-v1", 1, "<sha256-redirect>");

    private final String id;
    private final int version;
    private final String sha256;

    WebTemplateId(String id, int version, String sha256) {
        this.id = id;
        this.version = version;
        this.sha256 = sha256;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public String platform() {
        return "WEB";
    }
}
```

- [x] **Step 4: Compile**

```bash
sudo ./mvnw -pl backend -am -DskipTests compile
```

Expected: `BUILD SUCCESS`. (`TemplateReadinessValidator.validate(TemplateId, ...)` and `FileSystemTemplateReadinessValidator.manifestEntry(Path, TemplateId)` still compile unchanged for now — they're widened to `TemplateDescriptor` in Task 6.)

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/opshub/generation/domain/TemplateDescriptor.java \
        backend/src/main/java/com/opshub/generation/domain/TemplateId.java \
        backend/src/main/java/com/opshub/generation/domain/WebTemplateId.java
git commit -m "feat: add TemplateDescriptor and WebTemplateId"
```

---

### Task 5: Backend — platform-keyed `TemplateReadinessProperties`

**Files:**
- Modify: `backend/src/main/java/com/opshub/generation/application/TemplateReadinessProperties.java`

**Interfaces:**
- Produces: `TemplateReadinessProperties.getWebCatalogVersion()/setWebCatalogVersion(String)`, `getWebTemplateRoot()/setWebTemplateRoot(String)`, defaulting to `"web-v1"` / `"local-hub/templates/web"`.

- [x] **Step 1: Add the Web fields**

In `backend/src/main/java/com/opshub/generation/application/TemplateReadinessProperties.java`, add alongside the existing `catalogVersion`/`templateRoot` fields:

```java
    public static final String DEFAULT_WEB_CATALOG_VERSION = "web-v1";

    private String webCatalogVersion = DEFAULT_WEB_CATALOG_VERSION;
    private String webTemplateRoot = "local-hub/templates/web";

    public String getWebCatalogVersion() {
        return webCatalogVersion;
    }

    public void setWebCatalogVersion(String webCatalogVersion) {
        this.webCatalogVersion = webCatalogVersion;
    }

    public String getWebTemplateRoot() {
        return webTemplateRoot;
    }

    public void setWebTemplateRoot(String webTemplateRoot) {
        this.webTemplateRoot = webTemplateRoot;
    }
```

- [x] **Step 2: Compile**

```bash
sudo ./mvnw -pl backend -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [x] **Step 3: Commit**

```bash
git add backend/src/main/java/com/opshub/generation/application/TemplateReadinessProperties.java
git commit -m "feat: add platform-keyed catalog version/root to TemplateReadinessProperties"
```

---

### Task 6: Backend — platform-aware `TemplateReadinessValidator`

**Files:**
- Modify: `backend/src/main/java/com/opshub/generation/application/TemplateReadinessValidator.java`
- Modify: `backend/src/main/java/com/opshub/generation/application/FileSystemTemplateReadinessValidator.java`
- Test: `backend/src/test/java/com/opshub/generation/FileSystemTemplateReadinessValidatorTest.java`

**Interfaces:**
- Consumes: `TemplateDescriptor` (Task 4), `TemplateReadinessProperties.getWebCatalogVersion()/getWebTemplateRoot()` (Task 5).
- Produces: `TemplateReadinessValidator.validate(TemplateDescriptor, TemplateParameters): Readiness` (widened from `TemplateId`).
- Produces: `TemplateReadinessValidator.catalogVersion(String platform): String` (replaces the no-arg `catalogVersion()`).

- [x] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/opshub/generation/FileSystemTemplateReadinessValidatorTest.java`, after the existing test:

```java
    @Test
    void keepsWebTemplatesNotReadyWhenTheWebManifestCatalogVersionIsWrong() throws Exception {
        Files.writeString(tempDirectory.resolve("manifest.json"), """
                {"catalogVersion":"web-v2","templates":[]}
                """);
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        properties.setWebTemplateRoot(tempDirectory.toString());
        FileSystemTemplateReadinessValidator validator = new FileSystemTemplateReadinessValidator(properties, new ObjectMapper());

        TemplateReadinessValidator.Readiness readiness = validator.validate(
                com.opshub.generation.domain.WebTemplateId.OA_DELIVERY,
                new TestPlanService.TemplateParameters(
                        "Account", "https://example.test/thumb.png", "Header", "Body", "Open",
                        "https://example.test/path", "example.test"
                )
        );

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).contains("catalog version");
    }

    @Test
    void catalogVersionIsPlatformSpecific() {
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        FileSystemTemplateReadinessValidator validator = new FileSystemTemplateReadinessValidator(properties, new ObjectMapper());

        assertThat(validator.catalogVersion("ANDROID")).isEqualTo(TemplateReadinessProperties.DEFAULT_CATALOG_VERSION);
        assertThat(validator.catalogVersion("WEB")).isEqualTo(TemplateReadinessProperties.DEFAULT_WEB_CATALOG_VERSION);
    }
```

- [x] **Step 2: Run the tests to verify they fail**

```bash
sudo ./mvnw -pl backend -am -Dtest=FileSystemTemplateReadinessValidatorTest test
```

Expected: compile failure — `catalogVersion(String)` does not exist yet, and `validate` does not resolve the Web template root.

- [x] **Step 3: Widen the interface**

In `backend/src/main/java/com/opshub/generation/application/TemplateReadinessValidator.java`:

```java
package com.opshub.generation.application;

import com.opshub.generation.domain.TemplateDescriptor;

public interface TemplateReadinessValidator {
    Readiness validate(TemplateDescriptor template, TestPlanService.TemplateParameters parameters);

    static TemplateReadinessValidator alwaysReady() {
        return (template, parameters) -> Readiness.readyResult();
    }

    default String catalogVersion(String platform) {
        return "WEB".equals(platform)
                ? TemplateReadinessProperties.DEFAULT_WEB_CATALOG_VERSION
                : TemplateReadinessProperties.DEFAULT_CATALOG_VERSION;
    }

    record Readiness(boolean ready, String reason) {
        public static Readiness readyResult() {
            return new Readiness(true, null);
        }

        public static Readiness notReady(String reason) {
            return new Readiness(false, reason);
        }
    }
}
```

- [x] **Step 4: Make `FileSystemTemplateReadinessValidator` platform-aware**

In `backend/src/main/java/com/opshub/generation/application/FileSystemTemplateReadinessValidator.java`, change the import from `com.opshub.generation.domain.TemplateId` to `com.opshub.generation.domain.TemplateDescriptor`, then replace the `validate`, `catalogVersion`, and `manifestEntry` methods:

```java
    @Override
    public Readiness validate(TemplateDescriptor template, TestPlanService.TemplateParameters parameters) {
        try {
            Map<String, String> values = parameters.asMap();
            validateParameters(values);
            Path root = Path.of(rootFor(template.platform())).toAbsolutePath().normalize();
            JsonNode entry = manifestEntry(root, template, catalogVersion(template.platform()));
            Path templateFile = templateFile(root, entry);
            String templateSource = Files.readString(templateFile, StandardCharsets.UTF_8);
            if (!sha256(templateSource.getBytes(StandardCharsets.UTF_8)).equals(template.sha256())) {
                return Readiness.notReady("Template checksum does not match the approved catalog");
            }
            return compileRenderedTemplate(root, templateFile, render(templateSource, values));
        } catch (Exception exception) {
            return Readiness.notReady(exception.getMessage() == null ? "Template readiness validation failed" : exception.getMessage());
        }
    }

    @Override
    public String catalogVersion(String platform) {
        return "WEB".equals(platform) ? properties.getWebCatalogVersion() : properties.getCatalogVersion();
    }

    private String rootFor(String platform) {
        return "WEB".equals(platform) ? properties.getWebTemplateRoot() : properties.getTemplateRoot();
    }

    private JsonNode manifestEntry(Path root, TemplateDescriptor template, String expectedCatalogVersion) throws IOException {
        Path manifest = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("Template manifest is missing");
        }
        JsonNode catalog = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!expectedCatalogVersion.equals(catalog.path("catalogVersion").asText())) {
            throw new IllegalStateException("Template manifest catalog version does not match the configured catalog version");
        }
        JsonNode entries = catalog.path("templates");
        for (JsonNode entry : entries) {
            if (template.id().equals(entry.path("id").asText())) {
                if (entry.path("version").asInt(-1) != template.version()
                        || !template.sha256().equals(entry.path("sha256").asText())
                        || !"template-parameters-v1".equals(entry.path("parameterSchema").asText())) {
                    throw new IllegalStateException("Template manifest entry does not match the approved template");
                }
                return entry;
            }
        }
        throw new IllegalStateException("Template is missing from the manifest");
    }
```

Remove the now-unused `import com.opshub.generation.domain.TemplateId;` and add `import com.opshub.generation.domain.TemplateDescriptor;` in its place.

- [x] **Step 5: Run the tests to verify they pass**

```bash
sudo ./mvnw -pl backend -am -Dtest=FileSystemTemplateReadinessValidatorTest test
```

Expected: all pass, including the pre-existing `keepsTemplatesNotReadyWhenTheManifestCatalogVersionIsWrong` (unmodified — still exercises the Android/default root via `setTemplateRoot`).

- [x] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/generation/application/TemplateReadinessValidator.java \
        backend/src/main/java/com/opshub/generation/application/FileSystemTemplateReadinessValidator.java \
        backend/src/test/java/com/opshub/generation/FileSystemTemplateReadinessValidatorTest.java
git commit -m "feat: make TemplateReadinessValidator platform-aware"
```

---

### Task 7: Backend — `TestPlanService` selects templates by platform

**Files:**
- Modify: `backend/src/main/java/com/opshub/generation/application/TestPlanService.java`
- Test: `backend/src/test/java/com/opshub/generation/TestPlanServiceTest.java`

**Interfaces:**
- Consumes: `TemplateDescriptor`, `TemplateId`, `WebTemplateId` (Task 4); `TemplateReadinessValidator.catalogVersion(String)` (Task 6).
- Produces: `TestPlanService.generate` picks `TemplateId.values()` for `"ANDROID"` OAs or `WebTemplateId.values()` for `"WEB"` OAs, and stores the matching platform's catalog version.

- [x] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/opshub/generation/TestPlanServiceTest.java`, after `generatesFiveFixedCasesForTheCurrentRevisionWithParsedParameters` (after line 65):

```java
    @Test
    void generatesFiveWebCasesForAWebOperation() {
        Operation webOperation = operationWithOneWebAccount();
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(1, 0);
        TestPlanService service = new TestPlanService(
                entityManagerReturning(webOperation), jdbcTemplate, new ContentParser(),
                TemplateReadinessValidator.alwaysReady()
        );

        TestPlanService.TestPlanDto plan = service.generate(webOperation.getId(), webOperation.getRevision());

        assertThat(plan.testCases()).extracting(TestPlanService.TestCaseDto::templateId)
                .containsExactlyElementsOf(List.of(com.opshub.generation.domain.WebTemplateId.values()).stream()
                        .map(com.opshub.generation.domain.WebTemplateId::id).toList());
        assertThat(plan.testCases()).extracting(TestPlanService.TestCaseDto::order).containsExactly(1, 2, 3, 4, 5);
    }

    private static Operation operationWithOneWebAccount() {
        Operation operation = Operation.create("MOB-501");
        operation.addOfficialAccount(new OfficialAccount(
                operation, 1, "WEB", "Account", "https://cdn.example.test/thumb.png",
                "Header\nBody", "Open now", "https://business.example.test/offer?campaign=summer"
        ));
        return operation;
    }
```

- [x] **Step 2: Run the test to verify it fails**

```bash
sudo ./mvnw -pl backend -am -Dtest=TestPlanServiceTest test
```

Expected: `generatesFiveWebCasesForAWebOperation` fails — `createCases` still only iterates `TemplateId.values()` for every OA regardless of platform.

- [x] **Step 3: Update `TestPlanService`**

In `backend/src/main/java/com/opshub/generation/application/TestPlanService.java`, add the import:

```java
import com.opshub.generation.domain.TemplateDescriptor;
import com.opshub.generation.domain.WebTemplateId;
```

Replace `generate`'s catalog-version line (line 57):

```java
        requireFullyPassedValidation(operationId, revision);
        String catalogVersion = templateReadinessValidator.catalogVersion(platformFor(operation));
```

Replace `createCases` (lines 151-170):

```java
    private List<TestCaseDto> createCases(Operation operation, UUID planId) {
        List<TestCaseDto> cases = new ArrayList<>();
        for (OfficialAccount account : operation.getOfficialAccounts()) {
            TemplateParameters parameters = parametersFor(account);
            List<? extends TemplateDescriptor> templates = templatesFor(account.getPlatform());
            for (int index = 0; index < templates.size(); index++) {
                TemplateDescriptor template = templates.get(index);
                TemplateReadinessValidator.Readiness readiness;
                try {
                    readiness = templateReadinessValidator.validate(template, parameters);
                } catch (RuntimeException exception) {
                    readiness = TemplateReadinessValidator.Readiness.notReady("Template readiness validation failed");
                }
                cases.add(new TestCaseDto(
                        UUID.randomUUID(), planId, account.getOaOrder(), index + 1,
                        template.id(), template.version(), template.sha256(), parameters,
                        readiness.ready() ? "READY" : "NOT_READY", readiness.reason()
                ));
            }
        }
        return cases;
    }

    private static List<? extends TemplateDescriptor> templatesFor(String platform) {
        return "WEB".equals(platform) ? List.of(WebTemplateId.values()) : List.of(TemplateId.values());
    }

    private static String platformFor(Operation operation) {
        return operation.getOfficialAccounts().get(0).getPlatform();
    }
```

- [x] **Step 4: Run the tests to verify they pass**

```bash
sudo ./mvnw -pl backend -am -Dtest=TestPlanServiceTest test
```

Expected: all pass, including the pre-existing Android-focused tests (unchanged assertions, since `operationWithOneAccount()` is still `"ANDROID"`).

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/opshub/generation/application/TestPlanService.java \
        backend/src/test/java/com/opshub/generation/TestPlanServiceTest.java
git commit -m "feat: select templates and catalog version by OA platform"
```

---

### Task 8: Backend — `ExecutionService` reports the real platform in the job envelope

**Files:**
- Modify: `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`
- Test: `backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java`

**Interfaces:**
- Produces: `HubEnvelopeV1` built by `offerNextJob` carries the offered execution's actual OA platform (`"ANDROID"` or `"WEB"`) instead of the hardcoded literal `"ANDROID"`.

- [x] **Step 1: Give the existing `approvePlan` helper a platform parameter**

`backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java` has a private helper `approvePlan(UUID operationId, int revision)` (lines 320-344) that inserts a hardcoded `'ANDROID'` `official_accounts` row. Keep the existing two-argument overload (every current test calls it that way) and add a three-argument one it delegates to:

```java
    private UUID approvePlan(UUID operationId, int revision) {
        return approvePlan(operationId, revision, "ANDROID");
    }

    private UUID approvePlan(UUID operationId, int revision, String platform) {
        UUID planId = UUID.randomUUID();
        // buildJobOfferedEnvelope joins test_cases -> official_accounts on (operation_id,
        // oa_order) to populate the C1 oaOrder/oaName fields, so a row here is required for
        // offerNextJob to return any test cases at all.
        jdbcTemplate.update("""
                        INSERT INTO official_accounts (id, operation_id, oa_order, platform, oa_name, thumbnail_url, content, button_text, redirect_url)
                        VALUES (?, ?, 1, ?, 'Test OA', 'https://example.test/thumb.png', 'content', 'Open', 'https://example.test/redirect')
                        """, UUID.randomUUID(), operationId, platform);
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, ?, 'catalog-v1', 'READY', 'APPROVED')
                        """, planId, operationId, revision);
        for (int order = 1; order <= 5; order++) {
            jdbcTemplate.update("""
                            INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                            VALUES (?, ?, 1, ?, ?, 1, 'sha', '{}', 'READY')
                            """, UUID.randomUUID(), planId, order, "android-template-" + order);
        }
        jdbcTemplate.update("""
                        UPDATE operations SET status = 'APPROVED', plan_id = ?, approved_plan_id = ?, revision = ?
                        WHERE id = ?
                        """, planId, planId, revision, operationId);
        return planId;
    }
```

This replaces the existing `approvePlan` method (lines 320-344) with the two methods above.

- [x] **Step 2: Write the failing test**

Add to `ExecutionServiceTest`, after `leasesExactlyOneActiveJobPerHub` (after line 111):

```java
    @Test
    void reportsTheOperationsActualPlatformInTheJobOfferedPayload() {
        UUID operationId = createDraftOperation("MOB-607");
        approvePlan(operationId, 1, "WEB");
        executionService.start(operationId, 1, "key-web-platform");
        UUID hubId = UUID.randomUUID();
        hubConnectionService.markOnline(hubId, "WEBSOCKET");

        Optional<HubEnvelopeV1> offer = executionService.offerNextJob(hubId);

        assertThat(offer).isPresent();
        HubPayloads.JobOfferedPayload payload = (HubPayloads.JobOfferedPayload) offer.get().payload();
        assertThat(payload.platform()).isEqualTo("WEB");
    }
```

- [x] **Step 3: Run the test to verify it fails**

```bash
sudo ./mvnw -pl backend -am -Dtest=ExecutionServiceTest test
```

Expected: `reportsTheOperationsActualPlatformInTheJobOfferedPayload` fails — the payload's `platform` is currently always `"ANDROID"`.

- [x] **Step 4: Derive platform from the operation instead of hardcoding it**

In `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`, find `buildJobOfferedEnvelope` (starts at line 330) and its final construction line:

```java
                executionId, execution.idempotencyKey(), execution.sourceRevision(), "ANDROID", testCases, leaseToken);
```

Add a query for the platform right before that line, using the already-fetched `execution.planId()`:

```java
        String platform = jdbcTemplate.queryForObject("""
                        SELECT DISTINCT oa.platform
                        FROM official_accounts oa
                        JOIN test_plans plan ON plan.operation_id = oa.operation_id
                        WHERE plan.id = ?
                        """, String.class, execution.planId());
```

Then change the final construction line to use `platform` instead of the literal:

```java
                executionId, execution.idempotencyKey(), execution.sourceRevision(), platform, testCases, leaseToken);
```

(This query relies on the Task 2 invariant that every OA in an Operation shares one platform — `DISTINCT` returning more than one row would mean that invariant was violated elsewhere; `queryForObject` throwing `IncorrectResultSizeDataAccessException` in that case is an acceptable failure mode, not one this task needs to handle specially.)

- [x] **Step 5: Run the test to verify it passes**

```bash
sudo ./mvnw -pl backend -am -Dtest=ExecutionServiceTest test
```

Expected: passes, including all pre-existing tests in that file (Android fixtures still assert `platform == "ANDROID"`).

- [x] **Step 6: Commit**

```bash
git add backend/src/main/java/com/opshub/execution/application/ExecutionService.java \
        backend/src/test/java/com/opshub/execution/ExecutionServiceTest.java
git commit -m "fix: report the operation's actual platform in the Hub job envelope"
```

---

### Task 9: Local Hub — accept `WEB` in the job payload model

**Files:**
- Modify: `local-hub/src/opshub_hub/models.py`
- Test: `local-hub/tests/test_models.py`

**Interfaces:**
- Produces: `JobOfferedPayload.platform: Literal["ANDROID", "WEB"]`.
- Produces: `ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM: dict[str, tuple[str, ...]]` (replaces the single `ORDERED_TEST_CASE_TEMPLATE_IDS` tuple — keeps the old name as an alias for the `"ANDROID"` entry so nothing else referencing it by that name breaks).

- [x] **Step 1: Read the existing test file to match its fixture style**

```bash
grep -n "JobOfferedPayload\|ORDERED_TEST_CASE" local-hub/tests/test_models.py
```

- [x] **Step 2: Write the failing tests**

Add to `local-hub/tests/test_models.py` (adapt the exact `TestCase`/`TemplateParametersV1` construction helpers already used elsewhere in that file rather than redefining them):

```python
def test_job_offered_payload_accepts_web_platform_with_web_template_ids():
    payload = JobOfferedPayload(
        executionId=uuid4(),
        idempotencyKey="idem-web-1",
        revision=1,
        platform="WEB",
        testCases=[
            test_case(1, "OA One", 1, "web-oa-delivery-v1"),
            test_case(1, "OA One", 2, "web-thumbnail-v1"),
            test_case(1, "OA One", 3, "web-content-v1"),
            test_case(1, "OA One", 4, "web-button-text-v1"),
            test_case(1, "OA One", 5, "web-redirect-v1"),
        ],
        leaseToken=uuid4(),
    )
    assert payload.platform == "WEB"


def test_job_offered_payload_rejects_android_template_ids_for_a_web_platform_job():
    with pytest.raises(ValidationError):
        JobOfferedPayload(
            executionId=uuid4(),
            idempotencyKey="idem-web-2",
            revision=1,
            platform="WEB",
            testCases=[
                test_case(1, "OA One", 1, "android-oa-delivery-v1"),
                test_case(1, "OA One", 2, "android-thumbnail-v1"),
                test_case(1, "OA One", 3, "android-content-v1"),
                test_case(1, "OA One", 4, "android-button-text-v1"),
                test_case(1, "OA One", 5, "android-redirect-v1"),
            ],
            leaseToken=uuid4(),
        )
```

(If the file doesn't already import `pytest` and `ValidationError` from `pydantic`, add those imports. If there's no existing `test_case(...)` helper, build `TestCase(...)` instances directly the same way the file's existing Android tests do, just changing `templateId`.)

- [x] **Step 3: Run the tests to verify they fail**

```bash
cd local-hub && python -m pytest tests/test_models.py -k web -v
```

Expected: `test_job_offered_payload_accepts_web_platform_with_web_template_ids` fails with a `platform` validation error (currently `Literal["ANDROID"]`).

- [x] **Step 4: Update `models.py`**

In `local-hub/src/opshub_hub/models.py`, replace the `ORDERED_TEST_CASE_TEMPLATE_IDS` block and `JobOfferedPayload` (lines 70-128):

```python
# Fixed order/templateId per position per platform, mirroring $defs.OrderedTestCases'
# prefixItems in contracts/schemas/hub-envelope-v1.json.
ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM: dict[str, tuple[str, ...]] = {
    "ANDROID": (
        "android-oa-delivery-v1",
        "android-thumbnail-v1",
        "android-content-v1",
        "android-button-text-v1",
        "android-redirect-v1",
    ),
    "WEB": (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ),
}

# Retained for any existing import of the old single-platform name; equivalent to
# ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM["ANDROID"].
ORDERED_TEST_CASE_TEMPLATE_IDS: tuple[str, ...] = ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM["ANDROID"]


class JobOfferedPayload(StrictModel):
    executionId: UUID
    idempotencyKey: str = Field(min_length=1)
    revision: int = Field(ge=1)
    platform: Literal["ANDROID", "WEB"]
    testCases: list[TestCase]
    leaseToken: UUID

    @model_validator(mode="after")
    def validate_ordered_test_cases(self) -> "JobOfferedPayload":
        ordered_template_ids = ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM[self.platform]
        group_size = len(ordered_template_ids)
        value = self.testCases
        if len(value) == 0 or len(value) % group_size != 0:
            raise ValueError(
                f"testCases must contain a positive multiple of {group_size} items "
                f"(one group of {group_size} per OA), got {len(value)}"
            )

        num_groups = len(value) // group_size
        for group_index in range(num_groups):
            group = value[group_index * group_size:(group_index + 1) * group_size]
            expected_oa_order = group_index + 1
            group_oa_order = group[0].oaOrder
            if group_oa_order != expected_oa_order:
                raise ValueError(
                    f"testCases group {group_index} must have oaOrder {expected_oa_order}, "
                    f"got {group_oa_order}"
                )
            for offset, (test_case, expected_template_id) in enumerate(
                zip(group, ordered_template_ids)
            ):
                expected_order = offset + 1
                if test_case.oaOrder != group_oa_order:
                    raise ValueError(
                        f"testCases group {group_index} has inconsistent oaOrder: "
                        f"expected {group_oa_order}, got {test_case.oaOrder} at position {offset}"
                    )
                if test_case.order != expected_order:
                    raise ValueError(
                        f"testCases group {group_index} position {offset}: order must be "
                        f"{expected_order}, got {test_case.order}"
                    )
                if test_case.templateId != expected_template_id:
                    raise ValueError(
                        f"testCases group {group_index} position {offset}: templateId must be "
                        f"'{expected_template_id}', got '{test_case.templateId}'"
                    )
        return self
```

Change the existing import line near the top of the file:

```python
from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator
```

to:

```python
from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator
```

`field_validator` has exactly one usage in this file — the `validate_ordered_test_cases` method being replaced — so it's safe to drop from the import entirely.

- [x] **Step 5: Run the tests to verify they pass**

```bash
cd local-hub && python -m pytest tests/test_models.py -v
```

Expected: all pass, including every pre-existing Android-focused `JobOfferedPayload` test (the `"ANDROID"` branch of `ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM` is identical to the old single tuple).

- [x] **Step 6: Run the full Local Hub test suite**

```bash
cd local-hub && python -m pytest tests -q
```

Expected: all pass (checks nothing else imports `ORDERED_TEST_CASE_TEMPLATE_IDS` in a way the alias doesn't satisfy).

- [x] **Step 7: Commit**

```bash
git add local-hub/src/opshub_hub/models.py local-hub/tests/test_models.py
git commit -m "feat: accept WEB platform and web-*-v1 template ids in JobOfferedPayload"
```

---

### Task 10: Local Hub — optional `platform` in `HubConfig`

**Files:**
- Modify: `local-hub/src/opshub_hub/config.py`
- Test: `local-hub/tests/test_config.py` (create if it doesn't already exist — check first)

**Interfaces:**
- Produces: `HubConfig.platform: Literal["ANDROID", "WEB"]`, defaulting to `"ANDROID"` when `OPSHUB_PLATFORM` is unset (unlike the other five config vars, this one is optional).

- [x] **Step 1: Check for an existing config test file**

```bash
find local-hub/tests -iname "test_config.py"
```

If it exists, read it and follow its existing style for the new test below. If not, create it fresh as shown in Step 2.

- [x] **Step 2: Write the failing tests**

Add (or create the file with) these tests:

```python
from opshub_hub.config import load_config


def _base_env(**overrides):
    env = {
        "OPSHUB_BACKEND_URL": "https://backend.example.test",
        "OPSHUB_HUB_ID": "hub-1",
        "OPSHUB_HUB_TOKEN": "token",
        "OPSHUB_TEMPLATE_DIR": "/tmp/templates",
        "OPSHUB_WORK_DIR": "/tmp/work",
    }
    env.update(overrides)
    return env


def test_platform_defaults_to_android_when_unset():
    config = load_config(_base_env())
    assert config.platform == "ANDROID"


def test_platform_reads_from_env_when_set():
    config = load_config(_base_env(OPSHUB_PLATFORM="WEB"))
    assert config.platform == "WEB"
```

- [x] **Step 3: Run the tests to verify they fail**

```bash
cd local-hub && python -m pytest tests/test_config.py -v
```

Expected: `AttributeError` — `HubConfig` has no `platform` attribute yet.

- [x] **Step 4: Update `config.py`**

In `local-hub/src/opshub_hub/config.py`, add the field to `HubConfig` (after `data_root`):

```python
    platform: Literal["ANDROID", "WEB"] = "ANDROID"
```

Add `from typing import Literal` to the imports.

In `load_config`, read the optional env var without adding it to `_ENV_MAP` (which drives the "missing required variable" check):

```python
def load_config(env: dict | None = None) -> HubConfig:
    """Build a HubConfig from environment variables, raising if any are missing."""
    source = env if env is not None else os.environ
    missing = [name for name in _ENV_MAP.values() if not source.get(name)]
    if missing:
        raise ValueError(f"Missing required Local Hub environment variables: {', '.join(missing)}")
    platform = source.get("OPSHUB_PLATFORM") or "ANDROID"
    return HubConfig(
        backend_url=source[_ENV_MAP["backend_url"]],
        hub_id=source[_ENV_MAP["hub_id"]],
        hub_token=source[_ENV_MAP["hub_token"]],
        template_root=Path(source[_ENV_MAP["template_root"]]),
        data_root=Path(source[_ENV_MAP["data_root"]]),
        platform=platform,
    )
```

- [x] **Step 5: Run the tests to verify they pass**

```bash
cd local-hub && python -m pytest tests/test_config.py -v
```

Expected: both pass.

- [x] **Step 6: Commit**

```bash
git add local-hub/src/opshub_hub/config.py local-hub/tests/test_config.py
git commit -m "feat: add optional platform field to HubConfig"
```

---

### Task 11: Local Hub — Web preflight profile

**Files:**
- Modify: `local-hub/src/opshub_hub/preflight.py`
- Test: `local-hub/tests/test_preflight.py`

**Interfaces:**
- Produces: `run_web_preflight(*, template_root: Path, data_root: Path, chrome_profile_dir: Path, required_executables: tuple[str, ...] = ("node",), run_command: CommandRunner = _default_run_command, catalog_factory: Callable[[Path], TemplateCatalog] = TemplateCatalog) -> PreflightReport`.

- [x] **Step 1: Write the failing tests**

Add to `local-hub/tests/test_preflight.py`:

```python
from opshub_hub.preflight import run_web_preflight

WEB_TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates" / "web"


def test_web_preflight_passes_when_node_present_and_profile_exists(tmp_path):
    profile_dir = tmp_path / "chrome-profile"
    profile_dir.mkdir()

    def run_command(args, timeout=10.0):
        if args[-1] == "--version":
            return ProcessResult(returncode=0, stdout="v24.0.0\n")
        raise AssertionError(f"unexpected command {args}")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=profile_dir,
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
    )

    assert report.ok is True
    names = {check.name for check in report.checks}
    assert "executable:node" in names
    assert "chrome-profile-exists" in names
    assert "template-manifest-checksum" in names
    assert "writable:data-root" in names
    assert "adb-device-state" not in names
    assert "appium-reachable" not in names


def test_web_preflight_fails_when_chrome_profile_directory_is_missing(tmp_path):
    def run_command(args, timeout=10.0):
        return ProcessResult(returncode=0, stdout="v24.0.0\n")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=tmp_path / "does-not-exist",
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
    )

    assert report.ok is False
    failure = next(check for check in report.failures() if check.name == "chrome-profile-exists")
    assert "does-not-exist" in failure.detail
```

- [x] **Step 2: Run the tests to verify they fail**

```bash
cd local-hub && python -m pytest tests/test_preflight.py -k web -v
```

Expected: `ImportError` — `run_web_preflight` doesn't exist yet.

- [x] **Step 3: Implement `run_web_preflight`**

Add to `local-hub/src/opshub_hub/preflight.py`, after `run_preflight`:

```python
def run_web_preflight(
    *,
    template_root: Path,
    data_root: Path,
    chrome_profile_dir: Path,
    required_executables: tuple[str, ...] = ("node",),
    run_command: CommandRunner = _default_run_command,
    catalog_factory: Callable[[Path], TemplateCatalog] = TemplateCatalog,
) -> PreflightReport:
    """Preflight for the Web (WebdriverIO + Chrome) execution path: no adb, no Appium,
    no mobile device - Chrome resolves its own driver, and login is a pre-provisioned
    profile directory rather than a live device."""
    report = PreflightReport()

    for executable in required_executables:
        try:
            result = run_command([executable, "--version"], timeout=10.0)
            ok = result.returncode == 0
            detail = result.stdout.strip() or result.stderr.strip()
        except (OSError, subprocess.TimeoutExpired) as exc:
            ok = False
            detail = str(exc)
        report.checks.append(CheckResult(name=f"executable:{executable}", ok=ok, detail=detail))

    profile_ok = chrome_profile_dir.is_dir()
    report.checks.append(CheckResult(
        name="chrome-profile-exists",
        ok=profile_ok,
        detail="" if profile_ok else f"Chrome profile directory not found: {chrome_profile_dir} "
                                      "(complete the one-time manual QR login first)",
    ))

    try:
        catalog = catalog_factory(template_root)
        catalog.verify()
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=True))
    except TemplateIntegrityError as exc:
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=False, detail=str(exc)))

    try:
        data_root.mkdir(parents=True, exist_ok=True)
        probe_file = data_root / ".preflight-write-check"
        probe_file.write_text("ok")
        probe_file.unlink()
        report.checks.append(CheckResult(name="writable:data-root", ok=True))
    except OSError as exc:
        report.checks.append(CheckResult(name="writable:data-root", ok=False, detail=str(exc)))

    return report
```

- [x] **Step 4: Run the tests to verify they pass**

```bash
cd local-hub && python -m pytest tests/test_preflight.py -v
```

Expected: all pass, including every pre-existing `run_preflight` (Android) test, unmodified.

- [x] **Step 5: Commit**

```bash
git add local-hub/src/opshub_hub/preflight.py local-hub/tests/test_preflight.py
git commit -m "feat: add run_web_preflight (no adb/Appium, Chrome profile check)"
```

---

### Task 12: Local Hub — Web screenshot capture and command builder

**Files:**
- Create: `local-hub/src/opshub_hub/browser_control.py`
- Test: `local-hub/tests/test_browser_control.py`

**Interfaces:**
- Produces: `web_command_builder(spec_path: Path) -> list[str]` — matches `Runner`'s `command_builder: Callable[[Path], list[str]]`.
- Produces: `WebScreenshotCapturer.__call__(destination: Path) -> Path` — matches `Runner`'s `screenshot_capturer: Callable[[Path], Path]`.

Design note carried from the design spec: the WebdriverIO subprocess itself (via an `afterTest` hook in the operator-provisioned `wdio.web.conf.ts`, see Task 15's runbook update) writes each test's screenshot to a fixed, predictable path — `evidence/last-screenshot.png` relative to its own working directory (which `Runner` always sets to the job's `execution_dir`, i.e. the parent of `evidence/`). `WebScreenshotCapturer` doesn't take a new screenshot; it locates the one WebdriverIO already wrote and moves it to the attempt-specific `destination` `Runner` asks for, so two attempts (retry) or two test cases never collide on the same fixed filename.

- [x] **Step 1: Write the failing tests**

Create `local-hub/tests/test_browser_control.py`:

```python
from pathlib import Path

import pytest

from opshub_hub.browser_control import WebScreenshotCapturer, web_command_builder


def test_web_command_builder_runs_wdio_with_the_web_config():
    command = web_command_builder(Path("/exec/tests/web-oa-delivery-v1.spec.ts"))
    assert command == [
        "npx", "wdio", "run", "wdio.web.conf.ts", "--spec", "/exec/tests/web-oa-delivery-v1.spec.ts",
    ]


def test_screenshot_capturer_moves_the_fixed_screenshot_to_the_requested_destination(tmp_path):
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    source = evidence_dir / "last-screenshot.png"
    source.write_bytes(b"fake-png-bytes")
    destination = evidence_dir / "test-case-1-attempt1.png"

    capturer = WebScreenshotCapturer()
    result = capturer(destination)

    assert result == destination
    assert destination.read_bytes() == b"fake-png-bytes"
    assert not source.exists()


def test_screenshot_capturer_raises_when_wdio_never_wrote_a_screenshot(tmp_path):
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    destination = evidence_dir / "test-case-1-attempt1.png"

    capturer = WebScreenshotCapturer()

    with pytest.raises(FileNotFoundError):
        capturer(destination)
```

- [x] **Step 2: Run the tests to verify they fail**

```bash
cd local-hub && python -m pytest tests/test_browser_control.py -v
```

Expected: `ModuleNotFoundError` — `opshub_hub.browser_control` doesn't exist yet.

- [x] **Step 3: Implement `browser_control.py`**

Create `local-hub/src/opshub_hub/browser_control.py`:

```python
"""Web (WebdriverIO + Chrome) counterparts to appium_control.py's Android hooks -
command building and evidence capture for the Web execution path, which has no adb
and no Appium server to talk to."""

from __future__ import annotations

from pathlib import Path


def web_command_builder(spec_path: Path) -> list[str]:
    """Matches Runner's `command_builder: Callable[[Path], list[str]]`. Assumes
    wdio.web.conf.ts is present in the job's working directory (Runner sets `cwd` to
    `spec_path.parent.parent`), the same way the Android path assumes wdio.conf.ts is."""
    return ["npx", "wdio", "run", "wdio.web.conf.ts", "--spec", str(spec_path)]


class WebScreenshotCapturer:
    """Locates the screenshot the WebdriverIO subprocess already wrote (via its
    `afterTest` hook, to a fixed `evidence/last-screenshot.png` relative to its own
    working directory) and moves it to the attempt-specific path Runner requests.

    Matches the `Callable[[Path], Path]` signature `runner.Runner` expects for
    `screenshot_capturer`.
    """

    def __call__(self, destination: Path) -> Path:
        source = destination.parent / "last-screenshot.png"
        if not source.exists():
            raise FileNotFoundError(
                f"wdio.web.conf.ts did not write a screenshot to {source}; check its afterTest hook."
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        source.replace(destination)
        return destination
```

- [x] **Step 4: Run the tests to verify they pass**

```bash
cd local-hub && python -m pytest tests/test_browser_control.py -v
```

Expected: all pass.

- [x] **Step 5: Commit**

```bash
git add local-hub/src/opshub_hub/browser_control.py local-hub/tests/test_browser_control.py
git commit -m "feat: add Web command builder and screenshot capturer"
```

---

### Task 13: Local Hub — wire the Web execution path into `main.py`

**Files:**
- Modify: `local-hub/src/opshub_hub/main.py`
- Test: `local-hub/tests/test_main.py`

**Interfaces:**
- Consumes: `run_web_preflight` (Task 11), `web_command_builder`/`WebScreenshotCapturer` (Task 12), `HubConfig.platform` (Task 10).
- Produces: `build_web_runner(config, transport, outbox) -> Runner`; `run_forever` runs Android or Web preflight/runner construction based on `config.platform`.

- [x] **Step 1: Write the failing test**

Add to `local-hub/tests/test_main.py`:

```python
from opshub_hub.main import build_web_runner


def test_build_web_runner_uses_the_web_command_builder_and_screenshot_capturer(tmp_path):
    config = HubConfig(
        backend_url="https://backend.example.test",
        hub_id="hub-1",
        hub_token="token",
        template_root=Path(__file__).resolve().parents[1] / "templates" / "web",
        data_root=tmp_path,
        platform="WEB",
    )
    transport = FailoverTransport(ws_transport=_FakeTransport(), polling_transport=_FakeTransport())
    outbox = Outbox(tmp_path / "outbox.sqlite3")

    runner = build_web_runner(config, transport, outbox)

    assert runner._screenshot_capturer is not None
    assert runner._reset_appium_session is None
    assert runner._command_builder(Path("/exec/tests/x.spec.ts"))[2] == "wdio.web.conf.ts"
```

- [x] **Step 2: Run the test to verify it fails**

```bash
cd local-hub && python -m pytest tests/test_main.py -k web -v
```

Expected: `ImportError` — `build_web_runner` doesn't exist yet.

- [x] **Step 3: Add `build_web_runner` and platform dispatch**

In `local-hub/src/opshub_hub/main.py`, add imports:

```python
from opshub_hub.browser_control import WebScreenshotCapturer, web_command_builder
from opshub_hub.preflight import run_preflight, run_web_preflight
```

(Change the existing `from opshub_hub.preflight import run_preflight` line to the combined import above.)

Add `build_web_runner`, right after `build_runner`:

```python
def build_web_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.template_root)
    execution_root = config.data_root / "executions"
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url, hub_token=config.hub_token)
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=WebScreenshotCapturer(),
        reset_appium_session=None,
        command_builder=web_command_builder,
    )
```

In `run_forever`, replace the preflight call and runner construction:

```python
    if config.platform == "WEB":
        chrome_profile_dir = config.data_root / "chrome-profile"
        preflight = run_web_preflight(
            template_root=config.template_root, data_root=config.data_root, chrome_profile_dir=chrome_profile_dir
        )
    else:
        preflight = run_preflight(template_root=config.template_root, data_root=config.data_root)
    if not preflight.ok:
        for failure in preflight.failures():
            logger.error("Preflight check failed: %s (%s)", failure.name, failure.detail)
        raise SystemExit("Preflight checks failed; refusing to start the Local Hub.")
```

Later in the same function, replace `runner = build_runner(config, transport, outbox)` with:

```python
    runner = build_web_runner(config, transport, outbox) if config.platform == "WEB" else build_runner(config, transport, outbox)
```

- [x] **Step 4: Run the tests to verify they pass**

```bash
cd local-hub && python -m pytest tests/test_main.py -v
```

Expected: all pass, including the pre-existing Android `build_runner` test, unmodified.

- [x] **Step 5: Run the full Local Hub test suite**

```bash
cd local-hub && python -m pytest tests -q
```

Expected: all pass.

- [x] **Step 6: Commit**

```bash
git add local-hub/src/opshub_hub/main.py local-hub/tests/test_main.py
git commit -m "feat: wire the Web execution path into the Local Hub entrypoint"
```

---

### Task 14: Backend — spawn the Web worker when a Web execution starts

**Files:**
- Create: `backend/src/main/java/com/opshub/execution/application/WebWorkerLauncher.java`
- Create: `backend/src/main/java/com/opshub/execution/application/WebWorkerProperties.java`
- Modify: `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`
- Test: `backend/src/test/java/com/opshub/execution/WebWorkerLauncherTest.java`

**Interfaces:**
- Produces: `WebWorkerProperties` (`@ConfigurationProperties("opshub.web-worker")`) — `enabled` (default `false`), `pythonExecutable`, `workingDirectory`, `hubId`, `backendUrl`, `templateRoot`, `dataRoot`.
- Produces: `WebWorkerLauncher.launchIfNeeded(): void` — no-op when `enabled=false` or a launch is already in flight; otherwise starts the Web worker subprocess exactly once until it's known to have exited.
- Consumes (test seam): `WebWorkerLauncher.ProcessStarter { Process start(List<String> command, Path workingDirectory, Map<String,String> env) throws IOException; }`.

- [x] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/opshub/execution/WebWorkerLauncherTest.java`:

```java
package com.opshub.execution;

import com.opshub.execution.application.WebWorkerLauncher;
import com.opshub.execution.application.WebWorkerProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebWorkerLauncherTest {
    @Test
    void doesNothingWhenDisabled() {
        WebWorkerProperties properties = new WebWorkerProperties();
        properties.setEnabled(false);
        AtomicInteger startCount = new AtomicInteger();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            startCount.incrementAndGet();
            return new FakeProcess();
        });

        launcher.launchIfNeeded();

        assertThat(startCount.get()).isZero();
    }

    @Test
    void startsThePythonWorkerWithTheConfiguredCommandAndEnvironmentWhenEnabled() {
        WebWorkerProperties properties = enabledProperties();
        List<List<String>> commands = new ArrayList<>();
        List<Map<String, String>> envs = new ArrayList<>();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            commands.add(command);
            envs.add(env);
            return new FakeProcess();
        });

        launcher.launchIfNeeded();

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).containsExactly("python3", "-m", "opshub_hub.main");
        assertThat(envs.get(0))
                .containsEntry("OPSHUB_HUB_ID", "web-worker")
                .containsEntry("OPSHUB_PLATFORM", "WEB")
                .containsEntry("OPSHUB_BACKEND_URL", "https://backend.example.test");
    }

    @Test
    void doesNotStartASecondWorkerWhileOneIsStillRunning() {
        WebWorkerProperties properties = enabledProperties();
        AtomicInteger startCount = new AtomicInteger();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            startCount.incrementAndGet();
            return new FakeProcess();
        });

        launcher.launchIfNeeded();
        launcher.launchIfNeeded();

        assertThat(startCount.get()).isEqualTo(1);
    }

    private static WebWorkerProperties enabledProperties() {
        WebWorkerProperties properties = new WebWorkerProperties();
        properties.setEnabled(true);
        properties.setPythonExecutable("python3");
        properties.setWorkingDirectory("/opt/opshub/local-hub");
        properties.setHubId("web-worker");
        properties.setBackendUrl("https://backend.example.test");
        properties.setTemplateRoot("/opt/opshub/local-hub/templates/web");
        properties.setDataRoot("/opt/opshub/data/web-worker");
        return properties;
    }

    private static class FakeProcess extends Process {
        @Override
        public java.io.OutputStream getOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getInputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("still running");
        }

        @Override
        public void destroy() {
        }
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

```bash
sudo ./mvnw -pl backend -am -Dtest=WebWorkerLauncherTest test
```

Expected: compile failure — `WebWorkerLauncher`/`WebWorkerProperties` don't exist yet.

- [x] **Step 3: Create `WebWorkerProperties`**

Create `backend/src/main/java/com/opshub/execution/application/WebWorkerProperties.java`:

```java
package com.opshub.execution.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opshub.web-worker")
public class WebWorkerProperties {
    private boolean enabled = false;
    private String pythonExecutable = "python3";
    private String workingDirectory = "";
    private String hubId = "web-worker";
    private String backendUrl = "";
    private String templateRoot = "";
    private String dataRoot = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getHubId() {
        return hubId;
    }

    public void setHubId(String hubId) {
        this.hubId = hubId;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getTemplateRoot() {
        return templateRoot;
    }

    public void setTemplateRoot(String templateRoot) {
        this.templateRoot = templateRoot;
    }

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }
}
```

- [x] **Step 4: Create `WebWorkerLauncher`**

Create `backend/src/main/java/com/opshub/execution/application/WebWorkerLauncher.java`:

```java
package com.opshub.execution.application;

import com.opshub.hub.application.HubProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spawns the Web (WebdriverIO + Chrome) Local Hub as a subprocess on demand, the first
 * time it's needed, instead of requiring it to run as an always-on service like the
 * Android Hub. Disabled by default ({@code opshub.web-worker.enabled=false}) until an
 * operator has provisioned Chrome, Node, and a logged-in profile on the host running
 * the backend.
 */
@Component
public class WebWorkerLauncher {
    private final WebWorkerProperties properties;
    private final HubProperties hubProperties;
    private final ProcessStarter processStarter;
    private final ReentrantLock lock = new ReentrantLock();
    private Process runningProcess;

    public WebWorkerLauncher(WebWorkerProperties properties, HubProperties hubProperties) {
        this(properties, hubProperties, WebWorkerLauncher::startRealProcess);
    }

    WebWorkerLauncher(WebWorkerProperties properties, ProcessStarter processStarter) {
        this(properties, new HubProperties(), processStarter);
    }

    WebWorkerLauncher(WebWorkerProperties properties, HubProperties hubProperties, ProcessStarter processStarter) {
        this.properties = properties;
        this.hubProperties = hubProperties;
        this.processStarter = processStarter;
    }

    public void launchIfNeeded() {
        if (!properties.isEnabled()) {
            return;
        }
        lock.lock();
        try {
            if (runningProcess != null && runningProcess.isAlive()) {
                return;
            }
            List<String> command = List.of(properties.getPythonExecutable(), "-m", "opshub_hub.main");
            Map<String, String> env = Map.of(
                    "OPSHUB_BACKEND_URL", properties.getBackendUrl(),
                    "OPSHUB_HUB_ID", properties.getHubId(),
                    "OPSHUB_HUB_TOKEN", hubProperties.getSharedToken(),
                    "OPSHUB_TEMPLATE_DIR", properties.getTemplateRoot(),
                    "OPSHUB_WORK_DIR", properties.getDataRoot(),
                    "OPSHUB_PLATFORM", "WEB"
            );
            try {
                runningProcess = processStarter.start(command, Path.of(properties.getWorkingDirectory()), env);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start the Web worker process", exception);
            }
        } finally {
            lock.unlock();
        }
    }

    interface ProcessStarter {
        Process start(List<String> command, Path workingDirectory, Map<String, String> env) throws IOException;
    }

    private static Process startRealProcess(List<String> command, Path workingDirectory, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workingDirectory.resolve("web-worker.log").toFile()));
        builder.environment().putAll(env);
        return builder.start();
    }
}
```

- [x] **Step 5: Run the tests to verify they pass**

```bash
sudo ./mvnw -pl backend -am -Dtest=WebWorkerLauncherTest test
```

Expected: all pass.

- [x] **Step 6: Wire it into `ExecutionService.start`**

In `backend/src/main/java/com/opshub/execution/application/ExecutionService.java`, add a constructor parameter and field:

```java
    private final WebWorkerLauncher webWorkerLauncher;

    @Autowired
    public ExecutionService(JdbcTemplate jdbcTemplate, LeaseService leaseService, WebWorkerLauncher webWorkerLauncher) {
        this(jdbcTemplate, leaseService, webWorkerLauncher, new ObjectMapper());
    }

    ExecutionService(JdbcTemplate jdbcTemplate, LeaseService leaseService, WebWorkerLauncher webWorkerLauncher, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseService = leaseService;
        this.webWorkerLauncher = webWorkerLauncher;
        this.objectMapper = objectMapper;
    }
```

At the end of `start(...)`, right before the `return new ExecutionDto(...)` line, launch the worker if this operation is Web-platform:

```java
        String platform = jdbcTemplate.queryForObject(
                "SELECT platform FROM official_accounts WHERE operation_id = ? LIMIT 1", String.class, operationId);
        if ("WEB".equals(platform)) {
            webWorkerLauncher.launchIfNeeded();
        }
        return new ExecutionDto(executionId, operationId, operation.approvedPlanId(), expectedRevision, idempotencyKey, "QUEUED", now);
```

Existing tests that construct `ExecutionService` directly (not through Spring) now need a `WebWorkerLauncher` argument — search for them and pass a no-op one:

```bash
grep -rln "new ExecutionService(" backend/src/test
```

For each match, pass `new WebWorkerLauncher(new WebWorkerProperties(), (command, workingDirectory, env) -> { throw new UnsupportedOperationException(); })` (properties default to `enabled=false`, so `launchIfNeeded()` returns before ever touching the `ProcessStarter` — this is safe for every existing Android-only test).

- [x] **Step 7: Run the full backend test suite**

```bash
sudo ./mvnw -pl backend -am test
```

Expected: all pass (Testcontainers-dependent tests may be skipped in a sandbox without Docker — note that rather than blocking on it, per the existing precedent in `docs/superpowers/plans/2026-07-27-evidence-viewer.md` Task 1 Step 5).

- [x] **Step 8: Commit**

```bash
git add backend/src/main/java/com/opshub/execution/application/WebWorkerLauncher.java \
        backend/src/main/java/com/opshub/execution/application/WebWorkerProperties.java \
        backend/src/main/java/com/opshub/execution/application/ExecutionService.java \
        backend/src/test/java/com/opshub/execution/WebWorkerLauncherTest.java
git commit -m "feat: spawn the Web worker on demand when a Web execution starts"
```

---

### Task 15: Docs — runbook section for the Web platform

**Files:**
- Modify: `docs/operations/local-hub-runbook.md`

- [x] **Step 1: Add a Web platform section**

Append a new section to `docs/operations/local-hub-runbook.md`, after the existing Hub/device preflight section (mirror its table/list style):

```markdown
## Web (Zalo Web) platform

The Web execution path drives `chat.zalo.me` in desktop Chrome via
WebdriverIO — no Appium, no adb, no mobile device. The backend spawns this
worker itself (`opshub.web-worker.*` properties in `application.yml`/env,
disabled by default) rather than it running as an always-on service.

One-time setup on the host that will run it:

1. Provision a Node project containing `wdio.web.conf.ts` at its root,
   alongside the existing `wdio.conf.ts` used for Android (same
   `node_modules`/`package.json`, WebdriverIO v9 manages its own matching
   chromedriver — no separate driver install needed). `wdio.web.conf.ts`
   needs a plain `browserName: 'chrome'` capability with
   `'goog:chromeOptions': { args: ['--user-data-dir=<profile-dir>'] } }`,
   `maxInstances: 1`, and an `afterTest` hook that writes
   `await browser.saveScreenshot('./evidence/last-screenshot.png')` after
   every test (creating the `evidence/` directory first if it doesn't
   exist) — this is how `WebScreenshotCapturer`
   (`local-hub/src/opshub_hub/browser_control.py`) picks up each test's
   evidence.
2. Open that Chrome profile once manually and scan the Zalo QR login code
   with a dedicated test account's phone. The profile directory now stays
   signed in across runs; point `opshub.web-worker.data-root`'s
   `chrome-profile` subdirectory (or whichever path `wdio.web.conf.ts`'s
   `--user-data-dir` uses) at it.
3. Set `opshub.web-worker.enabled=true` and the rest of the
   `opshub.web-worker.*` properties (`python-executable`,
   `working-directory` — the Local Hub checkout with its Python venv
   already installed — `hub-id`, `backend-url`, `template-root` pointing
   at `local-hub/templates/web`, `data-root`) on the backend.

Unlike Android, this Hub isn't started manually or kept running — the
backend launches it (`WebWorkerLauncher`) the first time an operator starts
an execution against a `WEB`-platform Operation, and it exits once that
job completes. Sequential only: a second Web execution can't start while
one is already running, the same way a second execution against a
busy/leased Hub is rejected today.
```

- [x] **Step 2: Commit**

```bash
git add docs/operations/local-hub-runbook.md
git commit -m "docs: add Web platform runbook section"
```
