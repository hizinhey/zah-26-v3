/**
 * Thanh điều hướng chính của Zalo Web — về mặt hình ảnh là sidebar dọc bên
 * trái (không phải bottom bar như bản mobile), nhưng giữ cùng tên file/class
 * shape với bottom-tab-bar.page.android.ts để nhất quán giữa các platform.
 * Tab Messages ("Tin nhắn") luôn được chọn mặc định khi mở chat.zalo.me.
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
