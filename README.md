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
