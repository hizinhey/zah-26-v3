import zaloApp from '../pages/zalo-app.page.android';
import bottomTabBar from '../pages/bottom-tab-bar.page.android';
import messagesTab from '../pages/messages-tab.page.android';
import zBusinessChat from '../pages/zbusiness-chat.page.android';

// Mocha BDD interface không có sẵn global `test`, chỉ có `it` — alias lại để dùng `test`.
const test = it;

const OA_NAME = 'zBusiness';
const EXPECTED_REDIRECT_URL =
  'https://business.zbox.vn/nang-cap-business-lite?value_type=2&utm_source=zaloapp&utm_medium=oamess&utm_campaign=acquisition_friends';

describe('@tco-005 @smoke @android ZVAS - Check touch on button OA', () => {

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

  test('TCO_005: Check touch on button trên OA redirect đến đúng URL đã define', async () => {
    // Step 3: User touch button của message cuối cùng
    await zBusinessChat.tapLastCardButton();
    await driver.pause(1500);

    // Step 4: User quan sát URL được redirect
    //
    // Expected result: redirect mở đúng URL đã define (EXPECTED_REDIRECT_URL).
    //
    // checkRedirectUrl lấy full URL qua menu "..." > "Copy URL" > clipboard
    // (Zalo mở link này bằng WebView ngay trong app, và bản release không bật
    // WebView debugging nên không đọc được URL trực tiếp qua getContexts()).
    // So khớp origin+path+từng query param đã định nghĩa, bỏ qua tham số Zalo
    // tự chèn thêm (vd. "zarsrc").
    await zBusinessChat.checkRedirectUrl(EXPECTED_REDIRECT_URL);
  });

});
