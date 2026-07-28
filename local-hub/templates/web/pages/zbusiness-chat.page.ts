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
