# BT Proxy - Bluetooth Low Energy HTTP Bridge

Transform your old Android smartphone into a powerful Bluetooth Low Energy (BLE) hub that can be controlled via HTTP REST API calls.

## What is BT Proxy?

BT Proxy is an Android application that bridges the gap between Bluetooth Low Energy devices and HTTP-based automation systems. It allows you to:

- **Scan and connect** to multiple BLE devices simultaneously
- **Expose HTTP REST API** endpoints for each connected device
- **Read and write** BLE characteristics using simple HTTP requests
- **Control BLE devices** remotely from any system that can make HTTP calls

## Use Cases

- **Home Automation**: Control BLE sensors, lights, and devices from your smart home system
- **IoT Integration**: Bridge legacy BLE devices to modern cloud platforms
- **Remote Monitoring**: Access BLE device data from web applications or scripts
- **Automation Systems**: Integrate with tools like Home Assistant, Node-RED, or custom scripts
- **Development & Testing**: Debug BLE communications using simple HTTP tools like curl or Postman

## Features

- ✅ **Multiple Device Support**: Connect to and manage multiple BLE devices simultaneously
- ✅ **RESTful API**: Clean HTTP endpoints for reading/writing BLE characteristics
- ✅ **Authentication**: Configurable API key protection via `X-BtProxy` header
- ✅ **Real-time Logging**: Live API request/response logging for debugging
- ✅ **Bluetooth CTL Format**: Uses space-separated hex format (`A0 81 D0`) compatible with bluetoothctl
- ✅ **Foreground Service**: Runs in background to maintain connections
- ✅ **Simple UI**: Easy device selection and server configuration

## Quick Start

### Installation

1. Download and install the APK on your Android device (Android 8.0+ required)
2. Grant Bluetooth and Location permissions when prompted
3. Launch the app

### Setup

1. **Scan for Devices**: Tap "Start Scan" to discover nearby BLE devices
2. **Select Devices**: Check the devices you want to connect to
3. **Configure Server**: Set HTTP port (default: 8080) and auth token (default: "secret")
4. **Start Server**: Tap "Start HTTP Server" to begin

## API Reference

**All API responses are in PLAIN TEXT format - no JSON is used.**

### Public Endpoints (No Authentication Required)
```
GET /ping           → "pong"
GET /health         → "status: healthy\nserver: running\nconnected_devices_count: 2"
GET /test           → Server info and available endpoints
```

### Authenticated Endpoints

All authenticated API calls require the `X-BtProxy` header with your configured token.

```
Headers:
X-BtProxy: your_auth_token
```

### Read BLE Characteristic
```
GET /{mac_address}/{characteristic_uuid}
X-BtProxy: secret

Example:
GET /AA:BB:CC:DD:EE:FF/12345678-1234-1234-1234-123456789abc

Response (plain text):
A0 81 D0

Error Response:
ERROR: Device not connected
```

### Write BLE Characteristic
```
POST /{mac_address}/{characteristic_uuid}
X-BtProxy: secret
Content-Type: text/plain

Body: A0 81 D0

Response (plain text):
OK

Error Response:
ERROR: Failed to write characteristic
```

### Server Status
```
GET /status

Response (plain text):
status: running
connected_devices:
AA:BB:CC:DD:EE:FF
11:22:33:44:55:66
```

### List Connected Devices
```
GET /devices

Response (plain text - one MAC per line):
AA:BB:CC:DD:EE:FF
11:22:33:44:55:66
```

## Data Format

BT Proxy uses **space-separated hexadecimal bytes** format, compatible with `bluetoothctl`:

- ✅ **Valid**: `A0 81 D0`
- ✅ **Valid**: `FF 00 1A 2B`
- ❌ **Invalid**: `A081D0` (no spaces)
- ❌ **Invalid**: `0xA0 0x81 0xD0` (0x prefix not supported)

## Example Usage

### Using curl
```bash
# Test connectivity (no auth required)
curl http://192.168.1.100:8080/ping
# Response: pong

# Check server status
curl http://192.168.1.100:8080/health
# Response: status: healthy
#           server: running
#           connected_devices_count: 2

# Read a characteristic
curl -H "X-BtProxy: secret" \
  http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/12345678-1234-1234-1234-123456789abc
# Response: A0 81 D0

# Write to a characteristic
curl -X POST \
  -H "X-BtProxy: secret" \
  -d "A0 81 D0" \
  http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/12345678-1234-1234-1234-123456789abc
# Response: OK

# List connected devices
curl http://192.168.1.100:8080/devices
# Response: AA:BB:CC:DD:EE:FF
#           11:22:33:44:55:66
```

### Using JavaScript
```javascript
// Read characteristic (plain text response)
const response = await fetch('http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/uuid', {
  headers: { 'X-BtProxy': 'secret' }
});
const hexData = await response.text();
console.log('BLE data:', hexData); // "A0 81 D0"

// Write characteristic
await fetch('http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/uuid', {
  method: 'POST',
  headers: { 'X-BtProxy': 'secret' },
  body: 'FF 00 1A'
});
```

### Using Python
```python
import requests

headers = {'X-BtProxy': 'secret'}

# Read characteristic
response = requests.get('http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/uuid', headers=headers)
hex_data = response.text
print('BLE data:', hex_data)  # "A0 81 D0"

# Write characteristic
requests.post('http://192.168.1.100:8080/AA:BB:CC:DD:EE:FF/uuid', 
              headers=headers, data='FF 00 1A')
```

## Requirements

- **Android 8.0** (API level 26) or higher
- **Bluetooth Low Energy** support
- **Internet/WiFi** connection for HTTP API access

## Building from Source

### 🐳 Docker Method (Recommended - No Android SDK Required)

**Prerequisites:**
- **Docker** installed
- **ADB** for device deployment (optional)

**Quick Start:**
```bash
# Clone the repository
git clone https://github.com/yourusername/btproxy.git
cd btproxy

# Install Docker (Ubuntu/Debian)
sudo apt install docker.io

# Build and deploy (all-in-one command)
make docker-deploy
```

**Manual Docker Steps:**
```bash
# Build APK using Docker
make docker-build

# Install to device (requires ADB)
adb install -r btproxy-debug.apk
```

### 🔧 Local Method (Requires Android SDK)

**Prerequisites:**
- **Android SDK** installed and configured
- **ADB** (Android Debug Bridge) for device deployment  
- **Java 8+** for Gradle

**Setup Android SDK:**

**Option 1: Set environment variable**
```bash
export ANDROID_HOME=/path/to/android/sdk
```

**Option 2: Create local.properties**
```bash
echo 'sdk.dir=/usr/lib/android-sdk' > local.properties
```

**Option 3: Install via package manager (Ubuntu/Debian)**
```bash
sudo apt install android-sdk
echo 'sdk.dir=/usr/lib/android-sdk' > local.properties
```

**Build and Deploy:**
```bash
# Build and deploy to connected device
make deploy

# Or manually build with Gradle
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 📋 All Make Commands
```bash
# Docker builds (no SDK required)
make docker-build   # Build APK using Docker
make docker-deploy  # Build and install using Docker

# Local builds (requires Android SDK)  
make build          # Build APK only
make deploy         # Build and install to device
make release        # Build release APK

# Maintenance
make clean          # Clean build artifacts  
make devices        # Show connected devices
make uninstall      # Remove app from device
make help           # Show all commands
```

### 🚀 Recommended Workflow

1. **Install Docker:** `sudo apt install docker.io`
2. **Connect your Android device via USB**
3. **Enable USB debugging** in Developer Options
4. **Run:** `make deploy`

No Android SDK installation required! 🎉

## Debugging

The app includes a real-time log viewer showing all API requests and responses. Monitor the log area at the bottom of the main screen to debug your API calls and BLE communications.

## Security Notes

- Change the default authentication token from "secret" to something secure
- Use this app only on trusted networks
- The HTTP server is unencrypted - consider using it behind a VPN or secure network

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.