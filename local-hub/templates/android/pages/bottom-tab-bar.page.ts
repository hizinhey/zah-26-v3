class BottomTabBar {
  get messagesTab() {
    return $('id=com.zing.zalo:id/maintab_message');
  }

  async openMessagesTab(): Promise<void> {
    await this.messagesTab.click();
  }
}

export default new BottomTabBar();
