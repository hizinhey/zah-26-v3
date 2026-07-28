const sharp = require('sharp') as typeof import('sharp');

const RED_BADGE_THRESHOLD = { minRed: 150, maxGreen: 100, maxBlue: 100 };
const BADGE_REGION_WIDTH_RATIO = 0.2;

class MessagesTab {
  private readonly conversationListResourceId = 'com.zing.zalo:id/recycler_view_msgList';

  private conversationByNameOnScreen(name: string) {
    return $(`android=new UiSelector().textStartsWith("${name}")`);
  }

  conversationByName(name: string) {
    return $(
      'android=new UiScrollable(new UiSelector()' +
      `.resourceId("${this.conversationListResourceId}"))` +
      `.scrollIntoView(new UiSelector().textStartsWith("${name}"))`,
    );
  }

  async findConversation(name: string) {
    const onScreen = this.conversationByNameOnScreen(name);
    if (await onScreen.isDisplayed().catch(() => false)) {
      return onScreen;
    }
    const conversation = this.conversationByName(name);
    await conversation.waitForDisplayed();
    return conversation;
  }

  async openConversation(name: string): Promise<void> {
    await (await this.findConversation(name)).click();
  }

  async hasUnreadBadge(name: string): Promise<boolean> {
    const conversation = await this.findConversation(name);
    const location = await conversation.getLocation();
    const size = await conversation.getSize();
    const screenshot = Buffer.from(await driver.takeScreenshot(), 'base64');
    const { data, info } = await sharp(screenshot).extract({
      left: Math.round(location.x + size.width * (1 - BADGE_REGION_WIDTH_RATIO)),
      top: Math.round(location.y),
      width: Math.round(size.width * BADGE_REGION_WIDTH_RATIO),
      height: Math.round(size.height),
    }).raw().toBuffer({ resolveWithObject: true });
    for (let offset = 0; offset < data.length; offset += info.channels) {
      if (data[offset] >= RED_BADGE_THRESHOLD.minRed && data[offset + 1] <= RED_BADGE_THRESHOLD.maxGreen && data[offset + 2] <= RED_BADGE_THRESHOLD.maxBlue) return true;
    }
    return false;
  }
}

export default new MessagesTab();
