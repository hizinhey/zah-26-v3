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
    await driver.activateApp(this.packageName);
  }

  async isOpened(): Promise<boolean> {
    return (await driver.getCurrentPackage()) === this.packageName;
  }
}

export default new ZaloApp();
