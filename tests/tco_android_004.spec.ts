import zaloApp from '../pages/zalo-app.page.android';
import bottomTabBar from '../pages/bottom-tab-bar.page.android';
import messagesTab from '../pages/messages-tab.page.android';
import zBusinessChat from '../pages/zbusiness-chat.page.android';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';
const EXPECTED_BUTTON_TEXT = 'Nâng cấp ngay';

describe('@tco-004 @smoke @android ZVAS - Check UI button OA', () => {

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

  test('TCO_004: Check UI button trên OA hiển thị đúng theo design', async () => {
    // Step 3: User quan sát text trên button của message cuối cùng
    // Expected result: Button: "Nâng cấp ngay"
    await zBusinessChat.checkButtonText(EXPECTED_BUTTON_TEXT);
  });

});
