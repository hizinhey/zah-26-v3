// pixelmatch là pure ESM (package.json "type": "module") nên phải nạp bằng
// dynamic import() thay vì require(); sharp và https là CommonJS/builtin nên
// require() thường vẫn dùng được.
const sharp = require('sharp') as typeof import('sharp');
const https = require('https') as typeof import('https');

const OA_NAME = 'zBusiness';

// Ngưỡng chấp nhận sai khác khi so pixel giữa ảnh thumbnail thật (chụp từ màn
// hình, đã bị nén/co dãn theo kích thước hiển thị) và ảnh tham chiếu tải từ
// URL — không dùng 0% vì nén JPEG/scale luôn tạo sai khác nhỏ.
const THUMBNAIL_MAX_MISMATCH_RATIO = 0.05;

// Resource-id của rich-message card (OA gửi kèm thumbnail + header/body +
// button) — đã xác nhận trực tiếp trên thiết bị thật (không còn là giả định
// mượn từ phía Python nữa). Lưu ý: "layout_parent_message" và
// "richmessage_item_row" cũng được Zalo tái sử dụng cho một hàng quick-reply
// nhỏ khác trong cùng màn hình chat — đó là lý do lastRichCard phải lấy đúng
// occurrence chứa des_header/description, không phải occurrence [last()] một
// cách mù quáng nếu sau này có thêm rich-card khác xuất hiện sau nó.
const RICH_CARD_CONTAINER_ID = 'com.zing.zalo:id/layout_parent_message';
const RICH_CARD_ROW_ID = 'com.zing.zalo:id/richmessage_item_row';
const RICH_CARD_BUTTON_LABEL_ID = 'com.zing.zalo:id/description_item';
const RICH_CARD_THUMBNAIL_ID = 'com.zing.zalo:id/img_header';
const RICH_CARD_HEADER_ID = 'com.zing.zalo:id/des_header';
const RICH_CARD_BODY_ID = 'com.zing.zalo:id/description';

// Khi Zalo mở link bằng WebView ngay trong app (không phải trình duyệt
// ngoài — trường hợp thực tế của zBusiness), bản release không bật WebView
// debugging nên driver.getContexts()/getUrl() không đọc được full URL. Nút
// "..." trên action bar của màn hình WebView mở menu có mục "Copy URL" — đây
// là cách duy nhất lấy được full URL chính xác, qua clipboard sau khi chọn nó.
const WEBVIEW_OVERFLOW_MENU_XPATH =
    '//*[@resource-id="com.zing.zalo:id/zalo_action_bar"]//android.widget.FrameLayout[@clickable="true"]';
const COPY_URL_MENU_ITEM_XPATH = '//*[@text="Copy URL"]';

/**
 * Cửa sổ chat với OA zBusiness, mở ra từ tab Messages khi chọn hội thoại
 * "zBusiness". Action bar hiển thị tiêu đề OA và phụ đề "Official Account".
 */
class ZBusinessChatScreen {
    get backButton() {
        return $('~Back');
    }

    get title() {
        return $('id=com.zing.zalo:id/actionbar_txtTitle');
    }

    get subtitle() {
        return $('id=com.zing.zalo:id/actionbar_txtSubTitle');
    }

    get messageList() {
        return $('id=com.zing.zalo:id/chatlinelist');
    }

    get messageInput() {
        return $('id=com.zing.zalo:id/chatinput_text');
    }

    /** Chờ màn hình chat zBusiness tải xong (đúng tiêu đề "zBusiness"). */
    async waitForOpened() {
        await this.title.waitForDisplayed();
        await this.title.waitUntil(async () => (await this.title.getText()) === OA_NAME, {
            timeoutMsg: `Chat title did not become "${OA_NAME}"`,
        });
    }

    async isOpened(): Promise<boolean> {
        return (await this.title.isDisplayed()) && (await this.title.getText()) === OA_NAME;
    }

    async goBack() {
        await this.backButton.click();
    }

    /**
     * Rich-message card mới nhất (cuối cùng) trong lịch sử chat. Lọc theo
     * "chứa des_header" để không nhầm với hàng quick-reply nhỏ khác cũng
     * dùng chung resource-id layout_parent_message/richmessage_item_row.
     */
    get lastRichCard() {
        return $(
            `(//*[@resource-id="${RICH_CARD_CONTAINER_ID}"]` +
                `[.//*[@resource-id="${RICH_CARD_HEADER_ID}"]])[last()]`
        );
    }

    get lastRichCardThumbnail() {
        return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_THUMBNAIL_ID}"]`);
    }

    get lastRichCardHeader() {
        return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_HEADER_ID}"]`);
    }

    get lastRichCardBody() {
        return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_BODY_ID}"]`);
    }

    get lastRichCardButton() {
        return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_ROW_ID}"]`);
    }

    get lastRichCardButtonLabel() {
        return this.lastRichCard.$(`.//*[@resource-id="${RICH_CARD_BUTTON_LABEL_ID}"]`);
    }

    /**
     * Assert thumbnail của rich card cuối cùng đang hiển thị và khớp (theo
     * pixel) với ảnh tham chiếu tải về từ `expectedImageUrl`. Truyền thẳng
     * data mong đợi vào đây thay vì tự lấy dữ liệu thật rồi so sánh ở file test.
     */
    async checkThumbnailUrl(expectedImageUrl: string): Promise<void> {
        expect(await this.isLastCardThumbnailDisplayed()).toBe(true);
        expect(await this._isLastCardThumbnailMatching(expectedImageUrl)).toBe(true);
    }

    async isLastCardThumbnailDisplayed(): Promise<boolean> {
        return this.lastRichCardThumbnail.isDisplayed();
    }

    /**
     * true nếu ảnh thumbnail đang hiển thị khớp (theo pixel) với ảnh tham
     * chiếu tải về từ `referenceImageUrl`. Ảnh trên UI không có thuộc tính
     * URL/src để đọc trực tiếp qua accessibility, nên phải chụp đúng vùng
     * thumbnail rồi so pixel với ảnh tải về, thay vì so chuỗi URL.
     */
    private async _isLastCardThumbnailMatching(referenceImageUrl: string): Promise<boolean> {
        const { default: pixelmatch } = await import('pixelmatch');

        const thumbnail = this.lastRichCardThumbnail;
        await thumbnail.waitForDisplayed();
        const location = await thumbnail.getLocation();
        const size = await thumbnail.getSize();

        const screenshot = Buffer.from(await driver.takeScreenshot(), 'base64');
        const actual = await sharp(screenshot)
            .extract({
                left: Math.round(location.x),
                top: Math.round(location.y),
                width: Math.round(size.width),
                height: Math.round(size.height),
            })
            .ensureAlpha()
            .raw()
            .toBuffer({ resolveWithObject: true });

        const referenceBuffer = await this._downloadImage(referenceImageUrl);
        const reference = await sharp(referenceBuffer)
            .resize(actual.info.width, actual.info.height, { fit: 'fill' })
            .ensureAlpha()
            .raw()
            .toBuffer({ resolveWithObject: true });

        const totalPixels = actual.info.width * actual.info.height;
        const mismatchedPixels = pixelmatch(
            actual.data,
            reference.data,
            undefined,
            actual.info.width,
            actual.info.height,
            { threshold: 0.1 }
        );

        return mismatchedPixels / totalPixels <= THUMBNAIL_MAX_MISMATCH_RATIO;
    }

    private _downloadImage(url: string): Promise<Buffer> {
        return new Promise((resolve, reject) => {
            https
                .get(url, (response) => {
                    if (response.statusCode !== 200) {
                        reject(new Error(`Failed to download reference image (HTTP ${response.statusCode}): ${url}`));
                        response.resume();
                        return;
                    }
                    const chunks: Buffer[] = [];
                    response.on('data', (chunk) => chunks.push(chunk));
                    response.on('end', () => resolve(Buffer.concat(chunks)));
                    response.on('error', reject);
                })
                .on('error', reject);
        });
    }

    /** Assert header của rich card cuối cùng đúng bằng `expectedHeader`. */
    async checkHeaderText(expectedHeader: string): Promise<void> {
        const header = await this.lastRichCardHeader.getText();
        expect(header).toBe(expectedHeader);
    }

    /** Assert body của rich card cuối cùng đúng bằng `expectedBody`. */
    async checkBodyText(expectedBody: string): Promise<void> {
        const body = await this.lastRichCardBody.getText();
        expect(body).toBe(expectedBody);
    }

    /** Assert text trên button CTA của rich card cuối cùng đúng bằng `expectedText`. */
    async checkButtonText(expectedText: string): Promise<void> {
        const text = await this.getLastCardButtonText();
        expect(text).toBe(expectedText);
    }

    /**
     * Text trên button CTA của rich card cuối cùng. Accessibility text thật
     * có một ký tự newline thừa ở cuối (vd. "Nâng cấp ngay\n") — trim lại vì
     * đó là quirk hiển thị, không phải nội dung cần so khớp.
     */
    async getLastCardButtonText(): Promise<string> {
        const text = await this.lastRichCardButtonLabel.getText();
        return text.trim();
    }

    async tapLastCardButton(): Promise<void> {
        await this.lastRichCardButton.click();
    }

    /**
     * Assert đích đến sau khi chạm CTA/thumbnail khớp `expectedUrl`: so khớp
     * origin+path và toàn bộ query param của `expectedUrl`, bỏ qua tham số
     * Zalo tự chèn thêm vào mọi link đi ra (vd. "zarsrc" — tracking nội bộ,
     * không thuộc URL mà business định nghĩa).
     */
    async checkRedirectUrl(expectedUrl: string): Promise<void> {
        const actualUrl = await this._copyRedirectedUrl();
        const expected = new URL(expectedUrl);
        const actual = new URL(actualUrl);

        expect(actual.origin + actual.pathname).toBe(expected.origin + expected.pathname);
        for (const [key, value] of expected.searchParams) {
            expect(actual.searchParams.get(key)).toBe(value);
        }
    }

    /**
     * Lấy full URL của trang WebView đang mở, bằng cách mở menu "..." trên
     * action bar rồi chọn "Copy URL" và đọc lại từ clipboard — bản release
     * của Zalo không bật WebView debugging nên đây là cách duy nhất lấy được
     * full URL (driver.getContexts() chỉ thấy NATIVE_APP, không có context
     * WEBVIEW để gọi getUrl() trực tiếp).
     */
    private async _copyRedirectedUrl(): Promise<string> {
        await $(WEBVIEW_OVERFLOW_MENU_XPATH).click();
        const copyUrlMenuItem = $(COPY_URL_MENU_ITEM_XPATH);
        await copyUrlMenuItem.waitForDisplayed();
        await copyUrlMenuItem.click();

        const clipboardBase64 = await driver.getClipboard();
        return Buffer.from(clipboardBase64, 'base64').toString('utf-8');
    }
}

export default new ZBusinessChatScreen();
