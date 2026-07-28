class BottomTabBar {
  get messagesTab() {
    return $('id=com.zing.zalo:id/maintab_message');
  }

  async openMessagesTab(): Promise<void> {
    // waitForDisplayed (rather than relying on click()'s own implicit wait) makes it fail with
    // a clear "bottom tab bar never appeared" error if the app didn't land on a screen that has
    // one - e.g. it's still on a conversation detail screen - instead of clicking whatever
    // stale element WebdriverIO resolves the selector to.
    await this.messagesTab.waitForDisplayed();
    await this.messagesTab.click();
  }
}

export default new BottomTabBar();
