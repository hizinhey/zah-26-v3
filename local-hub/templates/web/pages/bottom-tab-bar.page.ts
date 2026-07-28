/**
 * Zalo Web's main navigation is a left sidebar, not a bottom bar - this file keeps the
 * same name/shape as bottom-tab-bar.page.ts on the Android side for cross-platform
 * consistency. The Messages tab is selected by default when chat.zalo.me opens.
 */
class LeftNavBar {
  get messagesTab() {
    return $('div[data-id="div_Main_TabMsg"]');
  }

  get contactsTab() {
    return $('div[data-translate-title="STR_TAB_CONTACT"]');
  }

  async openMessagesTab(): Promise<void> {
    await this.messagesTab.click();
  }

  async openContactsTab(): Promise<void> {
    await this.contactsTab.click();
  }
}

export default new LeftNavBar();
