const ZALO_PACKAGE = 'com.zing.zalo';

class ZaloApp {
  readonly packageName = ZALO_PACKAGE;

  async isInstalled(): Promise<boolean> {
    return driver.isAppInstalled(this.packageName);
  }

  async open(): Promise<void> {
    if (!(await this.isInstalled())) {
      throw new Error(`Zalo app (${this.packageName}) is not installed on this device`);
    }
    // Always force a cold start: activateApp() alone resumes whatever screen Zalo was last
    // showing (e.g. a conversation left open by a previous attempt or a manual session), which
    // silently breaks every downstream step that assumes it's starting from the app's home
    // screen - bottomTabBar.openMessagesTab() then either no-ops or hits a stale element, and
    // the spec ends up asserting against whatever conversation happened to be on screen instead
    // of the OA under test.
    await driver.terminateApp(this.packageName).catch(() => undefined);
    await driver.activateApp(this.packageName);
  }

  async isOpened(): Promise<boolean> {
    return (await driver.getCurrentPackage()) === this.packageName;
  }
}

export default new ZaloApp();
