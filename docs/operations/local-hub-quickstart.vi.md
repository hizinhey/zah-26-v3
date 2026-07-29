# Hướng dẫn khởi động Local Hub (tóm tắt nhanh)

Tài liệu đầy đủ, chi tiết mọi bước xem tại `docs/operations/local-hub-runbook.md`.
File này chỉ tóm tắt các bước thực tế đã dùng để chạy Local Hub trên máy này,
kèm file `.env` mẫu để tham khảo/sao chép.

## 1. Cài đặt lần đầu

```bash
cd local-hub
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
$EDITOR .env   # điền các biến bên dưới
```

## 2. File `.env` mẫu

Một Hub (một process `python -m opshub_hub.main`) có thể chạy **nhiều platform
cùng lúc** (ANDROID + WEB), mỗi platform một thread riêng, dùng chung một
`OPSHUB_HUB_ID`/`OPSHUB_WORK_DIR`. Đây là `.env` thực tế đang chạy trên máy này:

```dotenv
OPSHUB_BACKEND_URL=https://zah-26.123c.vn
OPSHUB_HUB_ID=3c75ce1d-e42d-4f16-b20f-b358df58a175
OPSHUB_HUB_TOKEN=<lấy từ deploy/env/backend.env trên server backend, xem mục 3>
OPSHUB_TEMPLATE_DIR=/Users/sol/Projects/zah-26-v2/local-hub/templates
OPSHUB_WORK_DIR=/Users/sol/Projects/zah-26-v2/local-hub/data
OPSHUB_PLATFORMS=ANDROID,WEB
OPSHUB_WDIO_PROJECT_DIR=/Users/sol/Projects/zah-26-v2/mobile_script
OPSHUB_NODE_EXECUTABLE=/Users/sol/.nvm/versions/node/v26.4.0/bin/node
```

Giải thích từng biến:

| Biến | Ý nghĩa |
|---|---|
| `OPSHUB_BACKEND_URL` | URL backend OpsHub |
| `OPSHUB_HUB_ID` | UUID định danh Hub này (tự sinh 1 lần, `python3 -c "import uuid; print(uuid.uuid4())"`) |
| `OPSHUB_HUB_TOKEN` | Secret dùng chung, phải khớp với backend |
| `OPSHUB_TEMPLATE_DIR` | Thư mục **cha** chứa `templates/android/` và `templates/web/` (không trỏ thẳng vào `android/`) |
| `OPSHUB_WORK_DIR` | Thư mục ghi dữ liệu (outbox, execution, evidence, journal, chrome-profile...) — dùng chung cho mọi platform, mỗi platform tự có file `journal-<platform>.sqlite3`/`outbox-<platform>.sqlite3` riêng |
| `OPSHUB_PLATFORMS` | Danh sách platform, phân tách bởi dấu phẩy: `ANDROID`, `WEB`, hoặc `ANDROID,WEB`. **Lưu ý:** biến cũ `OPSHUB_PLATFORM` (số ít) đã bị đổi tên, dùng biến này sẽ báo lỗi |
| `OPSHUB_WDIO_PROJECT_DIR` | Project WebdriverIO đã cài sẵn (`node_modules`, `tsconfig.json`, `wdio.conf.ts` cho ANDROID và/hoặc `wdio.web.conf.ts` cho WEB) |
| `OPSHUB_NODE_EXECUTABLE` | Đường dẫn Node.js >=20 (không dùng `node` mặc định trên PATH) |

## 3. Lấy `OPSHUB_HUB_TOKEN`

```bash
# Trên máy chạy backend:
sudo cat deploy/env/backend.env | grep OPSHUB_HUB_TOKEN
```
Copy nguyên giá trị vào `local-hub/.env`. Một backend chỉ có một token duy nhất,
mọi Hub kết nối tới backend đó dùng chung giá trị này.

## 4. Điều kiện riêng cho từng platform

### ANDROID
- `adb devices -l` phải thấy đúng 1 thiết bị, trạng thái `device` (không phải
  `unauthorized`/trống danh sách — nếu trống nghĩa là dây USB/kết nối bị rớt,
  cần cắm lại hoặc bật lại USB debugging trên điện thoại).
- Appium server đang chạy, `curl http://127.0.0.1:4723/status` trả 200.
- App Zalo (`com.zing.zalo`) đã cài trên máy.
- **Serial thiết bị (device ID) không nằm trong `.env`** — nó được khai cứng
  trong `capabilities` của `mobile_script/wdio.conf.ts`:
  ```ts
  capabilities: [{
      platformName: 'Android',
      'appium:deviceName': 'R5CW33GTS7R',   // <- serial thiết bị
      ...
  }],
  ```
  Lấy serial bằng `adb devices -l` (cột đầu tiên). Nếu đổi sang máy/thiết bị
  test khác, phải sửa trực tiếp giá trị này trong `wdio.conf.ts`, không sửa
  trong `.env` (`.env` chỉ trỏ tới *project* WebdriverIO qua
  `OPSHUB_WDIO_PROJECT_DIR`, không biết gì về từng thiết bị cụ thể).
- Cần biến môi trường `ANDROID_HOME`/`ANDROID_SDK_ROOT` (thường khai trong
  `~/.zshrc`) — **khi start Hub qua script/nohup không tương tác, các biến này
  có thể không được load**, xem mục 5 để chạy đúng cách.

### WEB
- Cần `mobile_script/wdio.web.conf.ts` (đã tạo sẵn, dùng chung
  `node_modules`/`tsconfig.json` với `wdio.conf.ts`).
- Cần thư mục Chrome profile đã đăng nhập sẵn tại
  `OPSHUB_WORK_DIR/chrome-profile` (ví dụ:
  `local-hub/data/chrome-profile`) — **làm 1 lần duy nhất**:
  ```bash
  open -na "Google Chrome" --args \
    --user-data-dir="/Users/sol/Projects/zah-26-v2/local-hub/data/chrome-profile" \
    "https://chat.zalo.me"
  ```
  Quét QR đăng nhập bằng tài khoản test chuyên dụng (không dùng tài khoản cá
  nhân, vì Hub sẽ tự động điều khiển trình duyệt này). Sau khi đăng nhập xong,
  **đóng hẳn cửa sổ Chrome này** trước khi start Hub — Chrome khoá
  (`SingletonLock`) thư mục profile khi đang mở, WebdriverIO sẽ không mở được
  session nếu profile đang bị khoá bởi một Chrome khác.
- Không cần adb/Appium. WebdriverIO v9 tự tải Chromedriver khớp phiên bản
  Chrome.

## 5. Chạy Hub

Vì `ANDROID_HOME` chỉ được khai trong `~/.zshrc` (chỉ load ở shell tương tác),
nên **không dùng `nohup ... &` trực tiếp trong shell không tương tác** — sẽ
thiếu biến này. Cách chạy an toàn:

```bash
cd local-hub
zsh -i -c '
  cd /Users/sol/Projects/zah-26-v2/local-hub
  set -a; source .env; set +a
  source .venv/bin/activate
  nohup python -m opshub_hub.main > /tmp/hub.log 2>&1 &
  disown
'
```

Kiểm tra:

```bash
ps aux | grep opshub_hub.main | grep -v grep
tail -f /tmp/hub.log
```

Log bình thường khi khởi động thành công (im lặng cho tới khi có request thật):
```
INFO:httpx:HTTP Request: GET http://127.0.0.1:4723/status "HTTP/1.1 200 OK"
```

Nếu một platform preflight fail (ví dụ ANDROID rớt kết nối thiết bị), platform
đó chỉ dừng thread của nó — platform còn lại (WEB) vẫn chạy bình thường trong
cùng process, log sẽ có dòng:
```
ERROR:opshub_hub:[ANDROID] Preflight check failed: ...
ERROR:opshub_hub:[ANDROID] Preflight checks failed; this platform will not run in this Hub process.
```
Sửa lỗi (cắm lại thiết bị, mở lại Appium...) rồi restart lại toàn bộ Hub
process để platform đó join lại.

## 6. Dừng / khởi động lại

```bash
pkill -f "opshub_hub.main"
# rồi chạy lại lệnh ở mục 5
```
