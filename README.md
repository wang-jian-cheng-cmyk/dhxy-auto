# DHXY Auto (Android + On-device Gateway)

This project contains:

- `app/`: Android APK execution layer (floating UI, screenshot capture, accessibility gestures)
- `gateway/`: local decision API on the phone (FastAPI + `opencode run`)

## What it does

1. You open game manually.
2. You tap floating `开始脚本`.
   - You can switch `模式: REAL / MOCK` from floating panel.
   - Use `测试点击` and `连接诊断` first if accessibility seems disconnected.
3. App captures screenshot.
4. App calls `http://127.0.0.1:8787/decide`.
5. Gateway calls `opencode run` and returns action JSON.
6. App executes `tap/swipe/wait/back/stop` and sleeps `next_capture_ms`.

Current screenshot transport uses multipart file upload (`image/png`) from APK to gateway, not base64 in command args.

## Fixed strategy files

- Goals: `gateway/goals.json`
- Hard prompt: `gateway/system_prompt.txt`

## Gateway startup (Termux)

```bash
cd ~/dhxy-auto/gateway
./run_gateway.sh
```

Or use direct startup scripts (recommended now):

```bash
cd ~/dhxy-auto/gateway
./start_gateway_real.sh
```

Mock mode:

```bash
cd ~/dhxy-auto/gateway
./start_gateway_mock.sh
```

Stop gateway:

```bash
cd ~/dhxy-auto/gateway
./stop_gateway.sh
```

Expected health check:

```bash
cd ~/dhxy-auto/gateway
./check_gateway.sh
```

## Recommended run order

1. Start gateway script first (`start_gateway_real.sh` or `start_gateway_mock.sh`).
2. Confirm `/health` works.
3. Open game.
4. Open floating panel and start script.

## Optional auto-start in Termux

If you use Termux:Boot, place a launcher script in `~/.termux/boot/`:

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/dhxy_gateway.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd ~/dhxy-auto/gateway
./start_gateway_real.sh
EOF
chmod +x ~/.termux/boot/dhxy_gateway.sh
```

## Accessibility diagnostics (ADB)

Use these helper scripts from `gateway/` when accessibility service shows enabled-but-not-running:

```bash
cd ~/dhxy-auto/gateway
./diagnose_accessibility.sh
```

Live filtered logcat:

```bash
cd ~/dhxy-auto/gateway
./watch_accessibility_logcat.sh
```

## VPN/Proxy split mode (recommended)

If full global VPN breaks other apps, keep system network normal and only proxy gateway/opencode traffic:

```bash
cd ~/dhxy-auto/gateway
cp proxy.env.example proxy.env
# edit proxy.env to match your local proxy ports
```

Then start as usual:

```bash
./start_gateway_real.sh
```

This keeps local loopback (`127.0.0.1`) direct so APK can still call gateway.

## Quick integration test (no model dependency)

Start gateway in mock mode:

```bash
cd ~/dhxy-auto/gateway
MOCK_DECISION=1 uvicorn main:app --host 127.0.0.1 --port 8787
```

Then call `/decide/mock` or normal `/decide` (both return a stable `tap` response in mock mode).

## One-image real decision test

```bash
cd ~/dhxy-auto/gateway
python test_decide_with_image.py --image /path/to/screenshot.jpg
```

## Android build

Open `/root/dhxy-auto` in Android Studio.

You can also build from CLI with Gradle wrapper:

```bash
cd /root/dhxy-auto
./build_apk.sh
```

If env vars are not set, export Android SDK path first:

```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
```

### Debug APK

- Menu: `Build > Build APK(s)`
- Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK

1. `Build > Generate Signed Bundle / APK`
2. Choose `APK`
3. Create or select keystore
4. Build release

Output: `app/build/outputs/apk/release/app-release.apk`

Keep the same keystore for updates.

## GitHub Actions build (phone-friendly)

If you are on mobile and cannot run Android Studio, use CI to compile APK:

1. Push this project to your GitHub repository.
2. Open GitHub `Actions` tab.
3. Run workflow: `Build Android APK` (or trigger by push).
4. After success, open the run and download artifact `dhxy-auto-debug-apk`.
5. Install the downloaded APK on your phone.

Workflow file is at `.github/workflows/android-apk.yml`.

## Required Android permissions

- Overlay permission
- Accessibility service enablement
- Screen capture permission (MediaProjection)
- Notifications (Android 13+)

## Notes

- This is an automation scaffold. Tune strategy and prompt before long runs.
- The app trusts local gateway only (`127.0.0.1`).
