import path from 'node:path';

// Zalo Web (chat.zalo.me) chỉ đăng nhập được qua quét mã QR bằng điện thoại —
// không tự động hoá được. Dùng một Chrome profile cố định (--user-data-dir)
// để giữ lại session sau lần đăng nhập thủ công đầu tiên; các lần chạy sau
// không cần quét QR lại. Thư mục này không được commit (dữ liệu đăng nhập cá nhân).
const CHROME_PROFILE_DIR = path.resolve(__dirname, '../.chrome-profile-pc-web');

export const config: WebdriverIO.Config = {
  runner: 'local',

  // Mọi spec dùng chung 1 Chrome profile (--user-data-dir) để giữ session
  // đăng nhập — nhiều Chrome cùng mở 1 profile cùng lúc sẽ báo lỗi profile
  // bị khoá, nên phải ép chạy tuần tự.
  maxInstances: 1,

  // Chỉ chạy đúng spec của platform PC Web — tránh việc glob rộng vô tình
  // chạy nhầm spec Android (tco_android_*) dưới capability Chrome.
  specs: [
    '../tests/tco_pc_web_*.spec.ts',
  ],

  // wdio v9 tự tải/quản lý chromedriver khớp bản Chrome đã cài trên máy
  // (qua @puppeteer/browsers) khi không khai báo hostname/port — không cần
  // Appium hay service driver riêng như phía Android.
  capabilities: [{
    maxInstances: 1,
    browserName: 'chrome',
    'goog:chromeOptions': {
      args: [`--user-data-dir=${CHROME_PROFILE_DIR}`],
    },
  }],

  framework: 'mocha',
  reporters: ['spec'],
  mochaOpts: {
    timeout: 120000
  }
};
