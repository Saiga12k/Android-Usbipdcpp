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

# Apply the tracked library fixes before building (also safe to run again)
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

`patches/usbipdcpp/0002-keep-session-on-submit-io-error.patch` keeps a failed
`libusb_submit_transfer()` with `LIBUSB_ERROR_IO` from marking the device as physically
removed, in both control and non-control paths. The failed URB still receives the
existing EPIPE response. Explicit `LIBUSB_ERROR_NO_DEVICE` and asynchronous
`LIBUSB_TRANSFER_NO_DEVICE` removal handling are preserved. This is a diagnostic fix;
it does not resolve the underlying I/O error or change ISO transfer scheduling.

Submission failures now log `seq`, endpoint address, transfer type, direction, requested
data length, and the numeric/text libusb error before releasing the transfer. For
non-control transfers the log also includes `iso_packets` and `packet_length` (the
first ISO packet's length; both fields are zero for non-ISO transfers). For example:

```text
submit failed: seq=1234 ep=0x81 type=ISO direction=IN length=393216 iso_packets=128 packet_length=3072 error=-1 (Input/Output Error)
```

All three GitHub Actions workflows apply the numbered patches in filename order
immediately after recursive checkout, before Gradle/CMake compile the library.
The script accepts already applied patches and fails on conflicts. The resulting modified submodule working tree
is expected; the parent repository stores the fix as a patch rather than an unpublished
submodule commit. After updating the library, review these patches against the new source;
remove each patch once its fix is available upstream.

To generate a Debug APK, run the existing **Build APK** workflow with `build_type=debug`.
It uses JDK 17 and NDK `29.0.14033849`, and uploads the APK as `usbipdcpp-debug`.
After extracting the artifact ZIP, enable USB debugging on POCO F7 and install it with:

```bash
adb devices
adb -s <POCO_SERIAL> install -r app-debug.apk
```

## 📄 License

[GPL-3.0](LICENSE)
