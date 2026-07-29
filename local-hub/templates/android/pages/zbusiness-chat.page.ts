const sharp = require('sharp') as typeof import('sharp');
const https = require('https') as typeof import('https');

const RICH_CARD_CONTAINER_ID = 'com.zing.zalo:id/layout_parent_message';
const RICH_CARD_ROW_ID = 'com.zing.zalo:id/richmessage_item_row';
const RICH_CARD_BUTTON_LABEL_ID = 'com.zing.zalo:id/description_item';
const RICH_CARD_THUMBNAIL_ID = 'com.zing.zalo:id/img_header';
const RICH_CARD_HEADER_ID = 'com.zing.zalo:id/des_header';
const RICH_CARD_BODY_ID = 'com.zing.zalo:id/description';
const WEBVIEW_SUBTITLE_ID = 'com.zing.zalo:id/action_bar_subtitle';
const BROWSER_URL_BAR_RESOURCE_IDS = [
  'com.android.chrome:id/url_bar', 'org.chromium.chrome:id/url_bar',
  'com.sec.android.app.sbrowser:id/url_bar', 'org.mozilla.firefox:id/mozac_browser_toolbar_url_view',
  'com.microsoft.emmx:id/url_bar', 'com.opera.browser:id/url_field',
];

class ZBusinessChatScreen {
  get title() { return $('id=com.zing.zalo:id/actionbar_txtTitle'); }
  get lastRichCard() {
    return $(`(//*[@resource-id="${RICH_CARD_CONTAINER_ID}"][.//*[@resource-id="${RICH_CARD_HEADER_ID}"]])[last()]`);
  }
  get lastRichCardThumbnail() { return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_THUMBNAIL_ID}"]`); }
  get lastRichCardHeader() { return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_HEADER_ID}"]`); }
  get lastRichCardBody() { return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_BODY_ID}"]`); }
  get lastRichCardButton() { return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_ROW_ID}"]`); }
  get lastRichCardButtonLabel() { return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_BUTTON_LABEL_ID}"]`); }

  async waitForOpened(oaName: string): Promise<void> {
    await this.title.waitForDisplayed();
    try {
      await this.title.waitUntil(async () => (await this.title.getText()) === oaName, { timeoutMsg: `Chat title did not become "${oaName}"` });
    } catch (err) {
      const actualTitle = await this.title.getText().catch(() => '<unreadable>');
      console.log(`[ZBusinessChatScreen] waitForOpened("${oaName}") failed — actual title was: "${actualTitle}"`);
      throw err;
    }
  }
  async isLastCardThumbnailDisplayed(): Promise<boolean> { return this.lastRichCardThumbnail.isDisplayed(); }
  async isLastCardThumbnailMatching(referenceImageUrl: string): Promise<boolean> {
    const { default: pixelmatch } = await import('pixelmatch');
    const thumbnail = this.lastRichCardThumbnail;
    await thumbnail.waitForDisplayed();
    const location = await thumbnail.getLocation();
    const size = await thumbnail.getSize();
    const rect = { left: Math.round(location.x), top: Math.round(location.y), width: Math.round(size.width), height: Math.round(size.height) };
    const screenshot = Buffer.from(await driver.takeScreenshot(), 'base64');
    const referenceBuffer = await this.downloadImage(referenceImageUrl);
    const actual = await sharp(screenshot).extract(rect).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    // fit: 'cover' (not 'fill'): the reference image's native aspect ratio rarely matches the
    // on-device thumbnail box exactly (e.g. 1844x1154 vs a 1364x880 crop - close but not equal).
    // 'fill' stretches non-uniformly to force an exact fit, which shifts every edge by a few
    // pixels and inflates the diff ratio well past the 5% threshold even for the *same* image
    // (verified: an identical pair scored 11.78% under 'fill' vs 1.26% under 'cover'). 'cover'
    // preserves the aspect ratio (cropping any excess) like the on-device thumbnail rendering
    // itself does, so it doesn't manufacture a mismatch out of a harmless ratio difference.
    const reference = await sharp(referenceBuffer).resize(actual.info.width, actual.info.height, { fit: 'cover' }).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    const diffRatio = pixelmatch(actual.data, reference.data, undefined, actual.info.width, actual.info.height, { threshold: 0.1 }) / (actual.info.width * actual.info.height);
    const matches = diffRatio <= 0.05;
    // Evidence capture (runner.py's _capture_and_upload_evidence) only ever grabs a full-screen
    // adb screenshot *after* the whole spec (incl. the after() hook's terminateApp) has finished
    // - by then Zalo is closed and the evidence photo is just the home screen, useless for
    // diagnosing a thumbnail *content* mismatch. So on a mismatch, save the two images that were
    // actually compared - the cropped on-device thumbnail and the resized reference - right here,
    // while we still have them, so a future failure can be visually inspected.
    if (!matches) {
      await this.saveThumbnailMismatchEvidence(screenshot, rect, referenceBuffer, actual.info.width, actual.info.height);
    }
    return matches;
  }
  private async saveThumbnailMismatchEvidence(
    screenshot: Buffer, rect: { left: number; top: number; width: number; height: number },
    referenceBuffer: Buffer, width: number, height: number,
  ): Promise<void> {
    try {
      const fs = require('fs') as typeof import('fs');
      const path = require('path') as typeof import('path');
      const evidenceDir = path.resolve(process.cwd(), 'evidence');
      fs.mkdirSync(evidenceDir, { recursive: true });
      await sharp(screenshot).extract(rect).png().toFile(path.join(evidenceDir, 'thumbnail-mismatch-actual.png'));
      await sharp(referenceBuffer).resize(width, height, { fit: 'cover' }).png().toFile(path.join(evidenceDir, 'thumbnail-mismatch-reference.png'));
      console.log(`[ZBusinessChatScreen] Thumbnail mismatch - saved actual/reference to ${evidenceDir}`);
    } catch (err) {
      console.log('[ZBusinessChatScreen] Failed to save thumbnail mismatch evidence:', err);
    }
  }
  private downloadImage(url: string): Promise<Buffer> {
    return new Promise((resolve, reject) => https.get(url, response => {
      if (response.statusCode !== 200) { reject(new Error(`Failed to download reference image (HTTP ${response.statusCode}): ${url}`)); response.resume(); return; }
      const chunks: Buffer[] = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve(Buffer.concat(chunks)));
      response.on('error', reject);
    }).on('error', reject));
  }
  async getLastCardHeaderAndBody(): Promise<{ header: string | null; body: string | null }> {
    return { header: await this.lastRichCardHeader.getText().catch(() => null), body: await this.lastRichCardBody.getText().catch(() => null) };
  }
  async getLastCardButtonText(): Promise<string> { return (await this.lastRichCardButtonLabel.getText()).trim(); }
  async tapLastCardButton(): Promise<void> { await this.lastRichCardButton.click(); }
  async getRedirectedDomain(): Promise<string | null> {
    const webviewContext = (await driver.getContexts()).find(context => String(context).toLowerCase().includes('webview'));
    if (webviewContext) {
      await driver.switchContext(webviewContext);
      try { return new URL(await driver.getUrl()).hostname; } finally { await driver.switchContext('NATIVE_APP'); }
    }
    for (const resourceId of BROWSER_URL_BAR_RESOURCE_IDS) {
      const urlBar = $(`id=${resourceId}`);
      if (await urlBar.isExisting()) return new URL(await urlBar.getText()).hostname;
    }
    const subtitle = $(`id=${WEBVIEW_SUBTITLE_ID}`);
    return (await subtitle.isExisting()) ? (await subtitle.getText()).trim() : null;
  }
}

export default new ZBusinessChatScreen();
