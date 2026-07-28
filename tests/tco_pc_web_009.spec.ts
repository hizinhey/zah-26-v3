import zaloApp from '../pages/zalo-app.page.PC_Web';
import bottomTabBar from '../pages/bottom-tab-bar.page.PC_Web';
import messagesTab from '../pages/messages-tab.page.PC_Web';
import zBusinessChat from '../pages/zbusiness-chat.page.PC_Web';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';
const EXPECTED_BUTTON_TEXT = 'Nâng cấp ngay';

describe('@tco_009 @pc_web ZVAS - Check UI button OA', () => {

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

  test('TCO_009: Check UI button trên OA hiển thị đúng theo design', async () => {
    // Step 4: User quan sát text trên button của message cuối cùng
    // Expected result: Button: "Nâng cấp ngay"
    await zBusinessChat.checkButtonText(EXPECTED_BUTTON_TEXT);
  });

});
