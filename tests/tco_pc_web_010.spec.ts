import zaloApp from '../pages/zalo-app.page.PC_Web';
import bottomTabBar from '../pages/bottom-tab-bar.page.PC_Web';
import messagesTab from '../pages/messages-tab.page.PC_Web';
import zBusinessChat from '../pages/zbusiness-chat.page.PC_Web';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';

// Cột Expected result trong sheet gốc bị lặp/dán nhầm URL 2 lần liền nhau
// ("...value_type=2https://business.zbox.vn/nang-cap-business-lite?value_type=2&utm_source=...").
// Đã xác nhận trực tiếp trên Zalo Web thật: URL redirect thực tế khớp với
// phần đầy đủ phía sau (giống hệt URL đã dùng ở bản mobile TCO_005/tco_android_005).
const EXPECTED_REDIRECT_URL =
  'https://business.zbox.vn/nang-cap-business-lite?value_type=2&utm_source=zalopc&utm_medium=oamess&utm_campaign=acquisition_friends';

describe('@tco_010 @pc_web ZVAS - Check click on button OA', () => {

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

  test('TCO_010: Check click on button trên OA redirect đến đúng URL đã define', async () => {
    // Step 4: User click button của message cuối cùng
    await zBusinessChat.tapLastCardButton();

    // Step 5: User quan sát URL được redirect
    //
    // Expected result: redirect mở đúng URL đã define (EXPECTED_REDIRECT_URL).
    //
    // Trên Web, CTA mở một tab trình duyệt mới (khác bản mobile phải mở
    // WebView trong app + đọc qua clipboard) — checkRedirectUrl chuyển sang
    // tab mới, so khớp origin+path+từng query param, bỏ qua tham số Zalo tự
    // chèn thêm (vd. "gidzl").
    await zBusinessChat.checkRedirectUrl(EXPECTED_REDIRECT_URL);
  });

});
