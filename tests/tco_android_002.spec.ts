import zaloApp from '../pages/zalo-app.page.android';
import bottomTabBar from '../pages/bottom-tab-bar.page.android';
import messagesTab from '../pages/messages-tab.page.android';
import zBusinessChat from '../pages/zbusiness-chat.page.android';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';

const EXPECTED_THUMBNAIL_URL =
  'https://res-zalo.zadn.vn/upload/media/2025/9/16/OA_new_style___friends_1757990197928_1307477.png';

describe('@tco-002 @smoke @android ZVAS - Check UI thumb OA', () => {

  // Pre-condition (thiết lập thủ công trước khi chạy, ngoài phạm vi tự động hoá):
  // - User đã nhận message OA zBusiness trên Zalo app Android

  beforeEach(async () => {
    // Step 1: User mở tab Messages
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();

    // Step 2: User mở CSC zBusiness
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  after(async () => {
    // Đóng Zalo sau khi file test này chạy xong, không để lại app mở trên thiết bị.
    await driver.terminateApp(zaloApp.packageName);
  });

  test('TCO_002: Check UI thumb trên OA hiển thị đúng theo design', async () => {
    // Step 3: User quan sát thumb trên message cuối cùng
    // Expected result: hiển thị đúng thumb OA (EXPECTED_THUMBNAIL_URL)
    await zBusinessChat.checkThumbnailUrl(EXPECTED_THUMBNAIL_URL);
  });

});
