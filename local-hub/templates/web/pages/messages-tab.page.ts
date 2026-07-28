class MessagesTab {
  get searchInput() {
    return $('#contact-search-input');
  }

  searchResultByName(name: string) {
    return $(
      `//div[contains(@class,"conv-item")][.//span[@class="txt-highlight" and text()="${name}"]]`
    );
  }

  conversationInMainListByName(name: string) {
    return $(
      `//div[contains(@class,"conv-item")]` +
        `[.//*[contains(@class,"conv-item-title__name")]//*[normalize-space(text())="${name}"]]`
    );
  }

  async searchConversation(name: string): Promise<void> {
    await this.searchInput.click();
    await this.searchInput.setValue(name);
    await this.searchResultByName(name).waitForDisplayed();
  }

  async openConversation(name: string): Promise<void> {
    if (await this.isConversationDisplayed(name)) {
      await this.conversationInMainListByName(name).click();
      return;
    }
    await this.searchConversation(name);
    await this.searchResultByName(name).click();
  }

  async isConversationDisplayed(name: string): Promise<boolean> {
    try {
      return await this.conversationInMainListByName(name).isDisplayed();
    } catch {
      return false;
    }
  }

  /**
   * Unconfirmed heuristic, carried over from the reference project: no real unread
   * conversation was available when this was written, so there's no confirmed example
   * to derive the exact badge class name from. Verify against a real unread OA
   * conversation before trusting this assertion (see the design spec's caveat).
   */
  async hasUnreadBadge(name: string): Promise<boolean> {
    const badge = this.conversationInMainListByName(name).$('.//*[contains(@class,"badge")]');
    return badge.isExisting();
  }
}

export default new MessagesTab();
