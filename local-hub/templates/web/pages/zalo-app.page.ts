const ZALO_WEB_URL = 'https://chat.zalo.me/';

/**
 * Zalo Web login only happens by scanning a QR code with a phone that already has Zalo
 * signed in - it cannot be automated. Chrome uses a fixed profile (--user-data-dir,
 * configured in wdio.web.conf.ts) so the QR scan only has to happen once; later runs
 * start already signed in.
 */
class ZaloWebApp {
  async open(): Promise<void> {
    await browser.url(ZALO_WEB_URL);
    await this._dismissSyncPromptIfPresent();
  }

  async isOpened(): Promise<boolean> {
    return (await browser.getUrl()).includes('chat.zalo.me');
  }

  private async _dismissSyncPromptIfPresent(): Promise<void> {
    const dismissButton = $('button*=Tôi không muốn đồng bộ');
    if (await dismissButton.isExisting()) {
      await dismissButton.click();
    }
  }
}

export default new ZaloWebApp();
