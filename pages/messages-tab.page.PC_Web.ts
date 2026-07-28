/**
 * Tab "Messages" (Tin nhắn) của Zalo Web — danh sách hội thoại bên trái.
 * Ưu tiên tìm hội thoại ngay trong danh sách chính (không thao tác gì thêm);
 * chỉ dùng ô search (#contact-search-input, xác nhận trực tiếp từ DOM thật)
 * khi không thấy trong danh sách chính.
 */
class MessagesTab {
    get searchInput() {
        return $('#contact-search-input');
    }

    /** Kết quả tìm kiếm khớp tên — nằm trong span.txt-highlight của mỗi dòng kết quả. */
    searchResultByName(name: string) {
        return $(
            `//div[contains(@class,"conv-item")][.//span[@class="txt-highlight" and text()="${name}"]]`
        );
    }

    /** Hội thoại trong danh sách chính (không qua search) — tên nằm trong conv-item-title__name. */
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

    /**
     * Mở hội thoại theo tên: ưu tiên click thẳng trong danh sách chính của
     * tab Messages nếu đang hiển thị sẵn; chỉ tìm qua ô search khi không thấy.
     */
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
     * true nếu hội thoại theo tên trong danh sách chính đang có badge "chưa đọc".
     *
     * CHƯA XÁC NHẬN SỐNG: tại thời điểm viết, không có hội thoại nào đang ở
     * trạng thái chưa đọc trên tài khoản test (tab "Chưa đọc" rỗng), nên
     * không có ví dụ thật để lấy đúng class name. Heuristic dùng ở đây dựa
     * trên quy ước đặt tên đã xác nhận thật của Zalo Web (class
     * "z-noti-badge-container" cho badge trên icon sidebar) — tìm bất kỳ
     * phần tử con nào có class chứa "badge". Cần chạy lại để xác nhận/sửa
     * khi có tin nhắn chưa đọc thật xuất hiện.
     */
    async hasUnreadBadge(name: string): Promise<boolean> {
        const badge = this.conversationInMainListByName(name).$('.//*[contains(@class,"badge")]');
        return badge.isExisting();
    }
}

export default new MessagesTab();
