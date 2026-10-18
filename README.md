# Android-Usbipdcpp

📱🔌 An Android USB/IP server application for sharing USB devices over the network, based on [usbipdcpp](https://github.com/yunsmall/usbipdcpp), a dedicated library for developing USB/IP servers.

## ✨ Features

- 🔗 Share USB devices from Android to remote computers
- 📦 Multiple device support
- ⚙️ Configurable server port
- 🌐 English UI
- 📋 Real-time log viewer

## 📋 Requirements

| Item | Requirement |
|------|-------------|
| 🤖 Android | 8.1+ (API 28+) |
| 💻 Hardware | USB OTG support |
| 🖥️ Client | USB/IP client (e.g., `usbip` on Linux) |

## 🚀 Usage

```
Android Device                    Remote Computer
    │                                   │
    │  1. 🔌 Connect USB device         │
    │  2. ▶️ Start server               │
    │  3. ✅ Grant USB permission       │
    │  4. 📎 Bind device                │
    │                                   │
    │◀──────── Network ────────────────▶│
    │                                   │
    │                    5. 📋 usbip list -r <ip>
    │                    6. 🔗 usbip attach -r <ip> -b <busid>
```

## 🔨 Build

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/Saiga12k/Android-Usbipdcpp.git
cd Android-Usbipdcpp

# Or if already cloned, initialize submodules
git submodule update --init --recursive

# Apply the tracked library fix before building (also safe to run again)
bash scripts/apply-usbipdcpp-patches.sh

# Build with Android Studio (NDK required)
```

### USB/IP enumeration fix

The English app changes are cherry-picked from
[`electronixg/Android-Usbipdcpp@7b66afd`](https://github.com/electronixg/Android-Usbipdcpp/commit/7b66afd0617b15d21af6f5a0ea491368625df000).
The library submodule is pinned to
[`electronixg/usbipdcpp@379f1ad`](https://github.com/electronixg/usbipdcpp/commit/379f1ad1d45f8a28b2d2c2822a66971b4f20476c),
which uses the USB device address for `dev_num` in Android's file-descriptor binding path.

`patches/usbipdcpp/0001-fix-control-transfer-direction.patch` additionally fixes
`LibusbTransferOperator::transfer_is_in()`: control requests use the setup packet's
`bmRequestType`, while bulk, interrupt and isochronous transfers retain endpoint-based
direction detection. This allows successful IN control replies to include their data
stage, including the device descriptor requested during Windows enumeration.

All three GitHub Actions workflows apply the patch immediately after recursive
checkout, before Gradle/CMake compile the library. The script accepts an already
applied patch and fails on conflicts. The resulting modified submodule working tree
is expected; the parent repository stores the fix as a patch rather than an unpublished
submodule commit. After updating the library, review this patch against the new source;
remove it and its application steps once the fix is available upstream.

To generate a Debug APK, run the existing **Build APK** workflow with `build_type=debug`.
It uses JDK 17 and NDK `29.0.14033849`, and uploads the APK as `usbipdcpp-debug`.
After extracting the artifact ZIP, enable USB debugging on POCO F7 and install it with:

```bash
adb devices
adb -s <POCO_SERIAL> install -r app-debug.apk
```

## 📄 License

[GPL-3.0](LICENSE)
