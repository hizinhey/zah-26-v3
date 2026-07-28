// Rich-message card (OA gửi kèm thumbnail + header/body + button) — class name
// đã xác nhận trực tiếp trên chat.zalo.me thật. Không như bản mobile, thumbnail
// đọc được thẳng URL qua CSS background-image, không cần so pixel.
const RICH_CARD_SELECTOR = 'div[contains(@class,"card--oa")]'; // dùng trong XPath, xem lastRichCard
const CARD_THUMBNAIL_CLASS = 'oa-msg-header__img';
const CARD_HEADER_CLASS = 'oa-msg-header__title';
const CARD_BODY_CLASS = 'oa-msg-header__desc';
const CARD_BUTTON_ROW_CLASS = 'oa-msg-child';
const CARD_BUTTON_TEXT_CLASS = 'oa-msg-child__title';

class ZBusinessChatWeb {
    /** Rich-message card mới nhất (cuối cùng) trong lịch sử chat. */
    get lastRichCard() {
        return $(`(//${RICH_CARD_SELECTOR})[last()]`);
    }

    /** Chờ cửa sổ chat tải xong bằng cách chờ rich card cuối cùng hiển thị. */
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

    /**
     * Assert thumbnail của rich card cuối cùng khớp `expectedImageUrl`. Đọc
     * thẳng URL từ CSS `background-image` (web hiển thị ảnh qua CSS, không
     * qua thẻ <img>) — so khớp không phân biệt hoa/thường vì đã xác nhận
     * cùng một ảnh được tham chiếu với hoa/thường khác nhau giữa Web
     * ("oa_new_style") và mobile ("OA_new_style").
     */
    async checkThumbnailUrl(expectedImageUrl: string): Promise<void> {
        const actualUrl = await this._getThumbnailUrl();
        expect(actualUrl?.toLowerCase() ?? null).toBe(expectedImageUrl.toLowerCase());
    }

    private async _getThumbnailUrl(): Promise<string | null> {
        const backgroundImage = await this.lastCardThumbnail.getCSSProperty('background-image');
        const match = String(backgroundImage.value).match(/url\(["']?(.*?)["']?\)/);
        return match ? match[1] : null;
    }

    /** Assert header của rich card cuối cùng đúng bằng `expectedHeader`. */
    async checkHeaderText(expectedHeader: string): Promise<void> {
        expect(await this.lastCardHeader.getText()).toBe(expectedHeader);
    }

    /** Assert body của rich card cuối cùng đúng bằng `expectedBody`. */
    async checkBodyText(expectedBody: string): Promise<void> {
        expect(await this.lastCardBody.getText()).toBe(expectedBody);
    }

    /** Assert text trên button CTA của rich card cuối cùng đúng bằng `expectedText`. */
    async checkButtonText(expectedText: string): Promise<void> {
        expect((await this.lastCardButtonText.getText()).trim()).toBe(expectedText);
    }

    private _windowHandlesBeforeClick: string[] = [];

    /** Chạm CTA — trên Web, CTA của rich-message card mở một tab trình duyệt mới. */
    async tapLastCardButton(): Promise<void> {
        this._windowHandlesBeforeClick = await browser.getWindowHandles();
        await this.lastCardButtonRow.click();
    }

    /**
     * Assert đích đến sau khi chạm CTA khớp `expectedUrl`: chuyển sang tab
     * mới mở ra, so khớp origin+path và toàn bộ query param của
     * `expectedUrl`, bỏ qua tham số Zalo tự chèn thêm (vd. "gidzl" — tracking
     * nội bộ, không thuộc URL mà business định nghĩa; tương đương "zarsrc"
     * đã thấy ở bản mobile).
     */
    async checkRedirectUrl(expectedUrl: string): Promise<void> {
        await browser.waitUntil(
            async () => (await browser.getWindowHandles()).length > this._windowHandlesBeforeClick.length,
            { timeout: 10000, timeoutMsg: 'No new browser tab opened after tapping the CTA button' }
        );
        const handlesAfter = await browser.getWindowHandles();
        const newHandle = handlesAfter.find((handle) => !this._windowHandlesBeforeClick.includes(handle));
        await browser.switchToWindow(newHandle as string);

        // URL còn được JS phía đích thêm tham số tracking sau khi tải xong.
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
