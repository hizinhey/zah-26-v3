const ZALO_WEB_URL = 'https://chat.zalo.me/';

/**
 * Zalo Web (PC) ở cấp trình duyệt: mở URL và xác nhận đã đăng nhập.
 * Đăng nhập chỉ thực hiện được bằng cách quét mã QR bằng điện thoại đã có
 * Zalo đăng nhập — không tự động hoá được. Chrome dùng profile cố định khai
 * báo ở wdio.pc_web.conf.ts (--user-data-dir) nên chỉ cần quét QR thủ công
 * một lần; các lần chạy test sau sẽ tự động ở trạng thái đã đăng nhập.
 */
class ZaloWebApp {
    /** Mở https://chat.zalo.me/ và đóng popup "đồng bộ tin nhắn" nếu có. */
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
