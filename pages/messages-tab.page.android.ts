// Zalo vẽ badge "chưa đọc" (chấm đỏ hoặc số đỏ) bằng custom view, không expose
// qua accessibility tree (đã xác nhận bằng cách dump UI kể cả với
// ignoreUnimportantViews:false — hàng hội thoại vẫn chỉ là một node lá duy
// nhất). Vì vậy không thể query badge bằng resource-id/xpath; phải chụp màn
// hình và so màu pixel ở vùng bên phải của hàng hội thoại.
const sharp = require('sharp') as typeof import('sharp');

// Ngưỡng màu đỏ của badge (đo thực tế trên thiết bị ra khoảng RGB(255,90,90)):
// kênh đỏ cao, kênh xanh lá/xanh dương thấp rõ rệt.
const RED_BADGE_THRESHOLD = { minRed: 150, maxGreen: 100, maxBlue: 100 };
// Badge luôn nằm sát rìa phải của hàng hội thoại (cạnh ngày/giờ).
const BADGE_REGION_WIDTH_RATIO = 0.2;

/**
 * Tab "Messages" — tab đầu tiên khi mở Zalo, hiển thị danh sách hội thoại.
 * Mỗi hội thoại không có resource-id riêng, chỉ phân biệt được qua nội dung
 * text (tên hiển thị + ngày + tin nhắn cuối), nên phải tìm bằng UiSelector.
 */
class MessagesTab {
    private readonly conversationListResourceId = 'com.zing.zalo:id/recycler_view_msgList';

    get conversationList() {
        return $(`id=${this.conversationListResourceId}`);
    }

    /**
     * Locator động cho một hội thoại theo tên hiển thị (ví dụ "zBusiness").
     * Dùng UiScrollable để tự cuộn danh sách tới khi tìm thấy tên tương ứng.
     */
    conversationByName(name: string) {
        return $(
            'android=new UiScrollable(new UiSelector()' +
                `.resourceId("${this.conversationListResourceId}"))` +
                `.scrollIntoView(new UiSelector().textStartsWith("${name}"))`
        );
    }

    /** Tìm hội thoại theo tên hiển thị, chờ tới khi nó hiển thị trên màn hình. */
    async findConversation(name: string) {
        const conversation = this.conversationByName(name);
        await conversation.waitForDisplayed();
        return conversation;
    }

    async isConversationDisplayed(name: string): Promise<boolean> {
        try {
            return await this.conversationByName(name).isDisplayed();
        } catch {
            return false;
        }
    }

    /** Mở hội thoại theo tên hiển thị, ví dụ mở chat zBusiness từ tab Messages. */
    async openConversation(name: string) {
        const conversation = await this.findConversation(name);
        await conversation.click();
    }

    /**
     * true nếu hội thoại theo tên hiển thị đang có badge "chưa đọc" màu đỏ
     * (chấm đỏ hoặc số đỏ) ở bên phải — xác định bằng so màu pixel vì badge
     * không có resource-id để query trực tiếp (xem ghi chú đầu file).
     */
    async hasUnreadBadge(name: string): Promise<boolean> {
        const conversation = await this.findConversation(name);
        const location = await conversation.getLocation();
        const size = await conversation.getSize();

        const badgeRegion = {
            left: Math.round(location.x + size.width * (1 - BADGE_REGION_WIDTH_RATIO)),
            top: Math.round(location.y),
            width: Math.round(size.width * BADGE_REGION_WIDTH_RATIO),
            height: Math.round(size.height),
        };

        const screenshot = Buffer.from(await driver.takeScreenshot(), 'base64');
        const { data, info } = await sharp(screenshot)
            .extract(badgeRegion)
            .raw()
            .toBuffer({ resolveWithObject: true });

        for (let offset = 0; offset < data.length; offset += info.channels) {
            const red = data[offset];
            const green = data[offset + 1];
            const blue = data[offset + 2];
            if (
                red >= RED_BADGE_THRESHOLD.minRed &&
                green <= RED_BADGE_THRESHOLD.maxGreen &&
                blue <= RED_BADGE_THRESHOLD.maxBlue
            ) {
                return true;
            }
        }
        return false;
    }
}

export default new MessagesTab();
