export const config: WebdriverIO.Config = {
  runner: 'local',
  hostname: '127.0.0.1',
  port: 4723,

  // Chỉ có 1 thiết bị vật lý (BDG00006708) — giới hạn tổng số worker chạy
  // song song của cả lần chạy về 1, để tránh nhiều session tranh chấp cùng
  // một UiAutomator2 instrumentation trên cùng một máy.
  maxInstances: 1,

  // specs nằm ở mobile_script/tests, còn config này nằm ở mobile_script/config —
  // rootDir mặc định của wdio là thư mục chứa file config nên phải trỏ lùi 1 cấp (../).
  // Chỉ chạy đúng spec Android (tco_android_*) — từ khi có thêm platform PC
  // Web trong cùng thư mục tests/, glob rộng sẽ chạy nhầm spec đó dưới
  // capability Appium/Android.
  specs: [
    '../tests/tco_android_*.spec.ts',
    '../test/tco_android_*.spec.ts',    // thêm cả 2 phòng khi
  ],

  // Chỉ có 1 thiết bị vật lý (BDG00006708) — ép chạy tuần tự để tránh nhiều
  // worker tranh chấp điều khiển cùng một màn hình cùng lúc.
  capabilities: [{
    maxInstances: 1,
    platformName: 'Android',
    'appium:deviceName': 'BDG00006708',
    'appium:platformVersion': '11',
    'appium:automationName': 'UiAutomator2',
    'appium:appPackage': 'com.zing.zalo',
    'appium:appActivity': 'com.zing.zalo.ui.SplashActivity',
    'appium:noReset': true,
    'appium:newCommandTimeout': 240,
  }],

  framework: 'mocha',
  reporters: ['spec'],
  mochaOpts: {
    timeout: 120000
  }
};