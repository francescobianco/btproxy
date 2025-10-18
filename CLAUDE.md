# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **btproxy** - a Bluetooth Low Energy (BLE) proxy project designed to bridge communication between devices and BLE peripherals. The project is currently in planning phase, with comprehensive documentation in Italian describing the intended Android Kotlin implementation.

## Architecture Plan

Based on the NOTES.md documentation, this project will implement:

- **Android Kotlin Application**: Minimal command-line buildable Android app
- **BLE Service**: Foreground service for continuous BLE operations
- **Intent Bridge**: Integration with MacroDroid/Tasker for automation
- **Gradle Build System**: Command-line compilation without Android Studio

### Planned Project Structure
```
btproxy/
├─ build.gradle.kts           # Root build configuration
├─ settings.gradle.kts        # Gradle settings
├─ app/
│  ├─ build.gradle.kts        # App module build config
│  ├─ src/main/AndroidManifest.xml
│  ├─ src/main/java/com/example/bleproxy/
│  │   ├─ MainActivity.kt     # Launch activity
│  │   └─ BleService.kt       # Core BLE proxy service
```

## Development Commands

When the Android project is implemented, use these commands:

### Build Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Clean build
./gradlew clean

# Build release APK
./gradlew assembleRelease
```

### Installation Commands
```bash
# Install debug APK to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Uninstall app
adb uninstall com.example.bleproxy
```

### Development Workflow
1. Make code changes in Kotlin files
2. Run `./gradlew assembleDebug` to build
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Test on physical Android device

## Key Technical Details

- **Target SDK**: 34 (Android 14)
- **Minimum SDK**: 26 (Android 8.0) - required for BLE features
- **Permissions**: BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, FOREGROUND_SERVICE
- **Service Type**: Foreground service with `connectedDevice` type
- **Architecture**: Lightweight, command-line buildable without Android Studio

## Project Status

**IMPLEMENTED** - Complete Android Kotlin application structure with:

### Core Components
- **MainActivity**: BLE device scanning and selection UI
- **DeviceAdapter**: RecyclerView adapter for device list  
- **BleConnectionManager**: Handles multiple BLE device connections
- **HttpServerService**: Foreground service with Ktor HTTP server

### API Endpoints
- `GET /status` - Server status and connected devices
- `GET /{macAddress}/{characteristicUuid}` - Read BLE characteristic
- `POST /{macAddress}/{characteristicUuid}` - Write BLE characteristic
- `GET /devices` - List connected devices
- `POST /connect/{macAddress}` - Connect to device
- `POST /disconnect/{macAddress}` - Disconnect device

### Data Formats
POST requests accept:
- Raw hex strings: `"0102030A"`
- JSON format: `{"data": "0102030A"}` or `{"data": [1,2,3,10]}`

### Project Structure
```
btproxy/
├─ build.gradle.kts           ✓ Root build configuration
├─ settings.gradle.kts        ✓ Gradle settings  
├─ app/
│  ├─ build.gradle.kts        ✓ App module (Ktor, Coroutines)
│  ├─ src/main/AndroidManifest.xml ✓ Permissions & services
│  ├─ src/main/res/           ✓ Layouts and resources
│  ├─ src/main/java/com/example/btproxy/
│  │   ├─ MainActivity.kt     ✓ Device selection UI
│  │   ├─ DeviceAdapter.kt    ✓ Device list adapter
│  │   ├─ BleConnectionManager.kt ✓ BLE operations
│  │   └─ HttpServerService.kt ✓ HTTP API server
```