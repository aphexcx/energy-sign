# EnergySign Android Things Project

https://medium.com/aphex-cx/how-i-built-a-bluetooth-connected-1-meter-wide-840-led-marquee-totem-for-edc-and-how-you-can-6a4aaf06cc9d

This Android Things component of the (otherwise-arduino-powered) Energy Sign is designed to manage, display, and control
various message types for the sign. The Energy Sign serves as a totem at various festivals like Burning Man and EDC -
check out the medium post above!

## Features

- **Dynamic Message Handling**: The sign can handle various message types, including marquee messages, one-time
  messages, and utility messages.

- **Bluetooth Connectivity**: Communicate via Bluetooth, allowing adding, deleting, and remote management of messages.

- **File System Integration**: Saves and load user messages to/from the device's file system through
  the `MessageRepository` class.

- **BeatLinkData Integration**: Consumes beat-link information to show the currently playing track on my twitch streams
  and art car sets:)

- **Keyboard Input Handling**: Supports keyboard input too!

## Dependencies

Based on the code snippets provided:

- **Kotpref**: Used for initializing and managing shared preferences in a Kotlin-first way.

- **RxJava**: Used for handling asynchronous operations and reactive programming patterns.

- **Gson**: Used for JSON serialization and deserialization.

- **AndroidX Libraries**: For various utilities and lifecycle management.

(Note: The specific versions of these dependencies can be found in the sign's build.gradle file)

## Getting Started

To get started with the EnergySign:

1. Clone the repository.
2. Open in Android Studio.
3. Ensure all dependencies are correctly installed.
4. Build and run on an Android Things device.

## The whole sign on a Mac

The app and the sign's firmware can run against each other on one computer, with no hardware:
this app in the stock Android emulator, and the real firmware image in the firmware emulator
of [arduino-led-marquee](https://github.com/aphexcx/arduino-led-marquee) (`emulator/`), which
shows both faces of the sign in a terminal. The app's serial line is connected to the firmware
emulator in place of the Arduino. Everything above that line is the code that runs on the sign.

One command brings it up:

```sh
./gradlew :app:assembleDebug
tools/sign-on-mac.sh --firmware ../arduino-led-marquee
```

It starts an Android emulator (or uses the one that runs), installs the debug build, tells
the app where the sign is, starts the app, and then runs the firmware emulator with its view
in the terminal. `Ctrl-C` ends it and stops the Android emulator it started.

- **The keyboard** of the sign is the Android emulator's window: click it and type. The up
  arrow starts the jump game and jumps.
- **A command**, as a phone would send it: `tools/sign-on-mac.sh send '!game'` (the adb
  command hook of the debug build).
- **Without a window**, for a script: `--headless`, with `--record out.gif` (what the sign
  showed), `--frames out.jsonl` (every frame and every byte on the line) and `--duration 60`.
  Keys without the window: `tools/sign-on-mac.sh key up`.
- `--emu` and `--image` name the firmware emulator and the firmware image themselves;
  `--help` lists the rest.

Needs: the Android SDK with its emulator and one system image, and the firmware emulator
built (`make -C emulator` in arduino-led-marquee). The script makes a small virtual device of
the sign's own, `EnergySign`, the first time (nothing is downloaded). That first start takes
minutes, and its Android is busy with itself for some minutes more; the state is saved when it
stops, and from then on a start takes seconds.

**How the app knows.** The board has the Android Things library and the stock emulator does
not. Where the library is there, the line is the board's UART, as ever, and nothing is read
or set for it. Where it is not, the line is a TCP connection to the firmware emulator:
`10.0.2.2:14400` (the computer's own localhost, seen from inside the Android emulator, and the
firmware emulator's port), or the IPv4 address and port (`a.b.c.d:port`, no host name) in the
system property `debug.energysign.link`. The app looks for it every half second, so either side
can be started, or started again, first.

**Every adb command of the script names an Android emulator** (`-s emulator-<port>`) and it
refuses any other serial: a real sign on the computer's USB is never spoken to.

**What is not in it:**

- **Bluetooth from a phone.** The Android emulator has no radio a phone can reach. Commands
  go in through the adb hook, which hands them to the app as a Bluetooth write would.
- **The board's speed, and the sign's.** The computer is much faster than the board: the 40 to
  80 ms by which the board's sign link is late a few times a minute do not happen here. The
  firmware emulator holds its own clock while it waits for the app's answer, so what a slow
  answer does to the sign's timing is not what it does on the sign. And the app goes by the
  computer's clock while a recording goes by the sign's: when the firmware emulator does not
  keep up with real time (its live view says how fast it runs), a recorded game plays faster
  than it was. How many frames a second the real sign shows is for the real sign to say.
- **A push by a break.** The line to the firmware emulator carries bytes, and a break is not
  bytes: a chooser push that asks for one (`!attn break`) fails here and says so. The push by
  a run of zeros is bytes and goes through.
- **The board's Android.** The board runs Android Things, API 27; the virtual device is a
  phone image of API 34 or 35. The app's code is the same, but what Android does around it is
  not: permissions are asked for at run time there (the script grants them at install), the
  rules for Bluetooth and for work in the background are newer, and there is no Things
  library, so the UART code is the one part that does not run here.
- **The sign's light.** Colours and brightness are a screen's.
