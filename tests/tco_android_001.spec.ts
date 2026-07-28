import zaloApp from '../pages/zalo-app.page.android';
import bottomTabBar from '../pages/bottom-tab-bar.page.android';
import messagesTab from '../pages/messages-tab.page.android';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';

describe('@tco-001 @smoke @android ZVAS - Check nhận được OA operation', () => {

  // Pre-condition (thiết lập thủ công trước khi chạy, ngoài phạm vi tự động hoá):
  // - User thuộc Whitelist nhận OA zBusiness
  // - User đã login Zalo app trên device Android
  // - Product owner đã trigger push OA zBusiness về tập Whitelist

  beforeEach(async () => {
    // Step 1: User mở Zalo app
    await zaloApp.open();

    // Step 2: User chọn tab Messages
    await bottomTabBar.openMessagesTab();
  });

  after(async () => {
    // Đóng Zalo sau khi file test này chạy xong, không để lại app mở trên thiết bị.
    await driver.terminateApp(zaloApp.packageName);
  });

  test('TCO_001: Check nhận đúng OA được operation', async () => {
    expect(await zaloApp.isOpened()).toBe(true);

    // Step 3: User quan sát message OA zBusiness ở tab Messages
    const conversation = await messagesTab.findConversation(OA_NAME);
    expect(await conversation.isDisplayed()).toBe(true);

    // Expected result: hiển thị số 1 đỏ hoặc dấu chấm đỏ new bên phải của message zBusiness
    expect(await messagesTab.hasUnreadBadge(OA_NAME)).toBe(true);
  });

});
