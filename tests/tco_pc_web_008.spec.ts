import zaloApp from '../pages/zalo-app.page.PC_Web';
import bottomTabBar from '../pages/bottom-tab-bar.page.PC_Web';
import messagesTab from '../pages/messages-tab.page.PC_Web';
import zBusinessChat from '../pages/zbusiness-chat.page.PC_Web';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';

const EXPECTED_HEADER = '💼 Mở rộng kết nối đến 5.000 liên hệ trên Zalo';
const EXPECTED_BODY =
  'Giữ trọn kết nối với mọi liên hệ quan trọng, không còn lo giới hạn danh bạ. Khám phá các đặc quyền nổi bật zBusiness Pro:\n' +
  '➤ Mở rộng kết nối: lưu trữ tới 5.000 liên hệ, không bỏ lỡ mối quan hệ nào\n' +
  '➤ Xây dựng cộng đồng riêng: sở hữu tới 10 cộng đồng để duy trì kết nối bền chặt\n' +
  '➤ Tối ưu hiệu suất công việc: bộ công cụ zBusiness Pro hỗ trợ quản lý và trò chuyện hiệu quả\n' +
  ' \n' +
  'Tìm hiểu và nâng cấp zBusiness Pro';

describe('@tco_008 @pc_web ZVAS - Check content OA', () => {

  // Pre-condition (thiết lập thủ công trước khi chạy, ngoài phạm vi tự động hoá):
  // - User đã nhận message OA zBusiness trên Zalo PC Web (Chrome)

  beforeEach(async () => {
    // Step 1: User mở tab Messages (bao gồm mở browser + paste URL Zalo PC Web)
    await zaloApp.open();
    await bottomTabBar.openMessagesTab();

    // Step 2: User click CSC zBusiness
    await messagesTab.openConversation(OA_NAME);
    await zBusinessChat.waitForOpened();
  });

  test('TCO_008: Check content trên OA hiển thị đúng theo design', async () => {
    // Step 3: User quan sát content header và body trên message cuối cùng
    // Expected result: header/body khớp chính xác nội dung đã define
    await zBusinessChat.checkHeaderText(EXPECTED_HEADER);
    await zBusinessChat.checkBodyText(EXPECTED_BODY);
  });

});
