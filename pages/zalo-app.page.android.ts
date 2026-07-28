const ZALO_PACKAGE = 'com.zing.zalo';

/**
 * Đại diện cho ứng dụng Zalo ở cấp app (không phải một màn hình cụ thể):
 * tìm xem Zalo đã được cài trên thiết bị chưa, rồi mở app một cách an toàn.
 * Nếu Zalo chưa cài, `open()` báo lỗi rõ ràng ngay lập tức thay vì gọi
 * activateApp và để Appium timeout mơ hồ khi chờ một app không tồn tại.
 */
class ZaloApp {
    readonly packageName = ZALO_PACKAGE;

    /** Kiểm tra Zalo có được cài trên thiết bị đang kết nối hay không. */
    async isInstalled(): Promise<boolean> {
        return driver.isAppInstalled(this.packageName);
    }

    /**
     * Mở Zalo nếu app đã được cài đặt trên thiết bị.
     * @throws Error với thông báo rõ ràng nếu Zalo chưa được cài.
     */
    async open(): Promise<void> {
        if (!(await this.isInstalled())) {
            throw new Error(`Zalo app (${this.packageName}) is not installed on this device`);
        }
        await driver.activateApp(this.packageName);
    }

    /** Package đang chạy ở foreground — dùng để xác nhận Zalo đã mở thành công. */
    async getForegroundPackage(): Promise<string> {
        return driver.getCurrentPackage();
    }

    /** true nếu Zalo hiện đang là app ở foreground. */
    async isOpened(): Promise<boolean> {
        return (await this.getForegroundPackage()) === this.packageName;
    }
}

export default new ZaloApp();
