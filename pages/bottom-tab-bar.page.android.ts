/**
 * Thanh điều hướng (tab bar) ở footer màn hình chính Zalo — cho phép chuyển
 * đổi qua lại giữa các tab: Messages, Contacts, Discovery, Timeline, Me.
 */
class BottomTabBar {
    get messagesTab() {
        return $('id=com.zing.zalo:id/maintab_message');
    }

    get contactsTab() {
        return $('id=com.zing.zalo:id/maintab_contact');
    }

    get discoveryTab() {
        return $('id=com.zing.zalo:id/maintab_discovery');
    }

    get timelineTab() {
        return $('id=com.zing.zalo:id/maintab_timeline');
    }

    get meTab() {
        return $('id=com.zing.zalo:id/maintab_metab');
    }

    async openMessagesTab() {
        await this.messagesTab.click();
    }

    async openContactsTab() {
        await this.contactsTab.click();
    }

    async openDiscoveryTab() {
        await this.discoveryTab.click();
    }

    async openTimelineTab() {
        await this.timelineTab.click();
    }

    async openMeTab() {
        await this.meTab.click();
    }
}

export default new BottomTabBar();
