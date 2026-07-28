import zaloApp from '../pages/zalo-app.page.PC_Web';
import bottomTabBar from '../pages/bottom-tab-bar.page.PC_Web';
import messagesTab from '../pages/messages-tab.page.PC_Web';
import zBusinessChat from '../pages/zbusiness-chat.page.PC_Web';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';
const EXPECTED_THUMBNAIL_URL =
  'https://res-zalo.zadn.vn/upload/media/2025/9/16/OA_new_style___friends_1757990197928_1307477.png';

describe('@tco_007 @pc_web ZVAS - Check UI thumb OA', () => {

  // Pre-condition (thiết lập thủ công trước khi chạy, ngoài phạm vi tự động hoá):
  // - User đã nhận message OA zBusiness trên Zalo PC Web (Chrome)

  beforeEach(async () => {
    // Step 1: User mở browser chrome
    // Step 2: User paste URL Zalo PC Web: https://chat.zalo.me/
    await zaloApp.open();

    // Step 3: User chọn tab Messages
    await bottomTabBar.openMessagesTab();

    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('TCO_007: Check UI thumb trên OA hiển thị đúng theo design', async () => {
    // Step 4: User quan sát thumb trên message cuối cùng
    // Expected result: hiển thị đúng thumb OA (EXPECTED_THUMBNAIL_URL)
    await zBusinessChat.checkThumbnailUrl(EXPECTED_THUMBNAIL_URL);
  });

});
