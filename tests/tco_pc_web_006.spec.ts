import zaloApp from '../pages/zalo-app.page.PC_Web';
import bottomTabBar from '../pages/bottom-tab-bar.page.PC_Web';
import messagesTab from '../pages/messages-tab.page.PC_Web';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';

describe('@tco_006 @pc_web ZVAS - Check nhận được OA operation', () => {

  // Pre-condition (thiết lập thủ công trước khi chạy, ngoài phạm vi tự động hoá):
  // - User thuộc Whitelist nhận OA zBusiness Zalo PC Web
  // - User đã login Zalo PC Web (Chrome) — xem ghi chú đăng nhập ở zalo-app.page.PC_Web.ts
  // - Product owner đã trigger push OA zBusiness về tập Whitelist

  beforeEach(async () => {
    // Step 1: User mở browser chrome
    // Step 2: User paste URL Zalo PC Web: https://chat.zalo.me/
    await zaloApp.open();

    // Step 3: User chọn tab Messages
    await bottomTabBar.openMessagesTab();
  });

  test('TCO_006: Check nhận đúng OA được operation', async () => {
    // Step 4: User quan sát message OA zBusiness ở tab Messages
    expect(await messagesTab.isConversationDisplayed(OA_NAME)).toBe(true);

    // Expected result: hiển thị số 1 đỏ hoặc dấu chấm đỏ new bên phải của message zBusiness
    //
    // Lưu ý: chưa xác nhận sống được tại thời điểm viết — tài khoản test hiện
    // không có hội thoại nào chưa đọc (tab "Chưa đọc" rỗng), nên chưa có ví
    // dụ thật để lấy đúng class badge. Xem ghi chú trong
    // messages-tab.page.PC_Web.ts (hasUnreadBadge).
    expect(await messagesTab.hasUnreadBadge(OA_NAME)).toBe(true);
  });

});
