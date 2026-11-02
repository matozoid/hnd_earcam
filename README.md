# EarCam

Clean viewers for cheap Wi-Fi in-ear (ear-wax) cameras. This replaces the vendor app
`com.xiaozhen.beauty.hnd` ("HND"). The vendor app asks for location, Bluetooth, storage and
system-settings access. None of that is needed to show a video stream.

- **Desktop** (Linux, Windows, macOS): Kotlin + Compose Multiplatform.
- **Android**: Kotlin + Jetpack Compose. Needs only `INTERNET` and `ACCESS_NETWORK_STATE`.
- **protocol**: plain Kotlin/JVM library with all camera logic. Both apps use it.
- **simulator**: fake cameras for testing without hardware.

## Use

1. Turn on the camera and connect to its Wi-Fi network.
2. Start EarCam. It finds the camera on its own: it takes the Wi-Fi gateway as the camera
   address (falling back to `192.168.169.1`) and tries all three known protocols at once.
   The first one that delivers a picture wins.
3. If that fails, type the camera IP in the *Camera IP* field.

Desktop shortcuts: `F11` fullscreen, `Esc` leave fullscreen. Snapshots go to `~/Pictures/EarCam`
(Android: `Pictures/EarCam`). They are the JPEG exactly as the camera sent it, without rotation.

*Auto-rotate* turns the picture using the camera's tilt sensor, like the vendor app.
*Rotate* and *Mirror* are manual and saved between runs.
*Circle* (on by default) shows the picture in a fixed round frame, so turning it shows no corners.

## Build

Needs JDK 21 (the Gradle wrapper downloads Gradle).

```sh
./gradlew :protocol:test                 # protocol tests against the fake cameras
./gradlew :desktop:run                   # run the desktop app
./gradlew :desktop:run --args=127.0.0.2  # ... with a fixed camera address
./gradlew :desktop:packageDeb            # .deb  (also packageRpm, packageAppImage)
./gradlew :desktop:packageMsi            # on Windows (needs WiX)
./gradlew :desktop:packageDmg            # on macOS
```

Packages land in `desktop/build/compose/binaries/main/`. jpackage only builds packages for
the OS it runs on.

### Android

The `:android` module is only part of the build when an Android SDK is configured
(`ANDROID_HOME` or `sdk.dir` in `local.properties`). Then:

```sh
./gradlew :android:assembleDebug   # android/build/outputs/apk/debug/
```

Or open the project in Android Studio. Minimum Android version: 10 (API 29).

On Android the app finds the Wi-Fi network (even one without internet access) and binds
its traffic to it. Otherwise Android would send the packets over mobile data.

## Testing without a camera

```sh
./gradlew :simulator:run --args="bl 127.0.0.2"     # or: txin, vii
./gradlew :desktop:run --args=127.0.0.2
```

The fake camera listens on `127.0.0.2`, so it can use the same port numbers as the app on one
Linux machine. It streams a moving test picture.

## Protocols

All three come from the vendor app (version 1.0.51). The app picks one by camera chipset.
All use UDP.

### BL (camera at 192.168.169.1, port 8800)

Implemented in the vendor's native `libbl_vii_jni.so`. See `BlProtocol.kt`.

- The client sends `ef 00 04 00` every 100 ms until video arrives.
- Video packet: `93 01 <u16 length>`, then `u64 seq @8`, `u64 cameraAck @0x18`,
  `u32 fragIdx @0x20`, `u32 fragCount @0x24`, `u32 dataLen @0x28`, `u16 width @0x2c`,
  `u16 height @0x2e`, `u8 quality @0x30`, and 1024 bytes of data from `0x38`.
  Fragment 0 also ends with an 11-byte tilt string, for example `+0123x-0456`.
- The data is JPEG scan data only. The client puts a stored JPEG header in front
  (standard IJG tables at quality 5/10/25/50/75/100, 4:4:4), patches in the size, and appends `FF D9`.
- The client sends a selective ACK every 25 ms: `ef 02 <u16 len> 02 02 00 01 <count>`, then
  entries `{u64 seq, u32 status, u32 len, bitmap…}` that list received fragments. The camera
  resends what is missing. Four frames can be in flight. Frames are delivered in order.
- Control messages from the camera (`93 04`) get the reply `ef 08 08 00 <u32 seq>`.
- After 2 s without data the client starts over.

### Beken TXIN (ports 44506 video, 52219 sensor)

- Discovery: `66 30 01 01` broadcast to port 46526. The camera answers.
- Start: `86 06 01` to port 52219 and `20 36` to port 44506. Stop: `20 37`.
- Video packet: `[frameId, isLast, packetNo (from 1), ?]`, then JPEG data. Packets of one
  frame are joined in order.
- Sensor packet: tilt angle as big-endian int16 at bytes 18–19.

### Vii Beken (port 7080)

- Start `20 36`, heartbeat `20 35` every 2 s, stop `20 37`.
- Video packet: `[frameId, isLast, packetCount, packetNo]`, 6 accelerometer bytes,
  2 more bytes, then JPEG data from byte 12.
- Rotation is the angle of the accelerometer's X/Y (little-endian int16 at sensor bytes 2 and 4),
  smoothed.

## Differences from the vendor app

- No permissions beyond network access. No analytics, ads or update checks.
- BL: a frame counts as complete only when *all* fragments have arrived. The vendor library
  skips the check for fragment 0, so it can show a frame without a header.
- BL: the ACK-layout fallback toggles once a second, not on every 1 ms tick.
- All three protocols are tried at the same time. The vendor app guesses one from the
  Wi-Fi name or IP address.
