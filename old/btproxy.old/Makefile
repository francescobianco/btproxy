# BT Proxy Android Build and Deployment Makefile

# Default target
.PHONY: all
all: deploy

# Build debug APK
.PHONY: build
build:
	@echo "🔨 Building debug APK..."
	@if [ -z "$(ANDROID_HOME)" ] && [ ! -f local.properties ]; then \
		echo "❌ Android SDK not configured. Choose one option:"; \
		echo "   1. Use Docker (no SDK required): make docker-build"; \
		echo "   2. Set ANDROID_HOME environment variable"; \
		echo "   3. Create local.properties with sdk.dir=/path/to/android/sdk"; \
		echo "   4. Install Android SDK: apt install android-sdk"; \
		echo ""; \
		echo "💡 Recommended: make docker-build (builds without local SDK)"; \
		exit 1; \
	fi
	./gradlew assembleDebug
	@echo "✅ Build complete: app/build/outputs/apk/debug/app-debug.apk"

# Build release APK
.PHONY: release
release:
	@echo "🔨 Building release APK..."
	./gradlew assembleRelease
	@echo "✅ Release build complete: app/build/outputs/apk/release/app-release.apk"

# Quick deploy to connected USB device
.PHONY: deploy
deploy:
	@echo "🚀 Building and deploying to USB device..."
	@if command -v docker >/dev/null 2>&1; then \
		echo "🐳 Attempting Docker build..."; \
		if ./docker-build.sh; then \
			echo "✅ Docker build successful!"; \
			adb install -r btproxy-debug.apk; \
		else \
			echo "❌ Docker build failed, falling back to local build..."; \
			if [ -z "$(ANDROID_HOME)" ] && [ ! -f local.properties ]; then \
				echo ""; \
				echo "⚠️  No Android SDK configured for local build."; \
				echo "Please either:"; \
				echo "  1. Fix Docker build issues"; \
				echo "  2. Install Android SDK: sudo apt install android-sdk"; \
				echo "  3. Set ANDROID_HOME or create local.properties"; \
				echo ""; \
				echo "Example: echo 'sdk.dir=/usr/lib/android-sdk' > local.properties"; \
				exit 1; \
			fi; \
			./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk; \
		fi; \
	else \
		echo "❌ Docker not found, using local build..."; \
		if [ -z "$(ANDROID_HOME)" ] && [ ! -f local.properties ]; then \
			echo "❌ Android SDK not configured. Please either:"; \
			echo "   1. Install Docker: sudo apt install docker.io"; \
			echo "   2. Install Android SDK: sudo apt install android-sdk"; \
			echo "   3. Set ANDROID_HOME or create local.properties"; \
			echo ""; \
			echo "Example: echo 'sdk.dir=/usr/lib/android-sdk' > local.properties"; \
			exit 1; \
		fi; \
		./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk; \
	fi
	@echo "✅ App deployed successfully!"

# Clean build artifacts
.PHONY: clean
clean:
	@echo "🧹 Cleaning build artifacts..."
	./gradlew clean
	@echo "✅ Clean complete"

# Install to device without rebuilding
.PHONY: install
install:
	@echo "📱 Installing existing APK to device..."
	@if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then \
		echo "❌ APK not found. Run 'make build' first."; \
		exit 1; \
	fi
	adb install -r app/build/outputs/apk/debug/app-debug.apk
	@echo "✅ Installation complete"

# Uninstall app from device
.PHONY: uninstall
uninstall:
	@echo "🗑️  Uninstalling app from device..."
	adb uninstall com.example.btproxy || echo "App was not installed"
	@echo "✅ Uninstall complete"

# Show connected devices
.PHONY: devices
devices:
	@echo "📱 Connected devices:"
	adb devices

# Show APK info
.PHONY: info
info:
	@echo "📦 APK Information:"
	@if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then \
		echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"; \
		ls -lh app/build/outputs/apk/debug/app-debug.apk; \
	else \
		echo "Debug APK: Not built"; \
	fi
	@if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then \
		echo "Release APK: app/build/outputs/apk/release/app-release.apk"; \
		ls -lh app/build/outputs/apk/release/app-release.apk; \
	else \
		echo "Release APK: Not built"; \
	fi

# Development workflow: clean, build, deploy
.PHONY: redeploy
redeploy: clean deploy

# Build with Docker (no Android SDK required)
.PHONY: docker-build
docker-build:
	@echo "🐳 Building APK with Docker (no local SDK required)..."
	@if ! command -v docker >/dev/null 2>&1; then \
		echo "❌ Docker not found. Please install Docker first:"; \
		echo "   Ubuntu/Debian: sudo apt install docker.io"; \
		echo "   macOS: brew install docker"; \
		echo "   Or visit: https://docs.docker.com/get-docker/"; \
		exit 1; \
	fi
	./docker-build.sh

# Deploy using Docker build
.PHONY: docker-deploy
docker-deploy: docker-build
	@echo "📱 Installing Docker-built APK..."
	@if ! command -v adb >/dev/null 2>&1; then \
		echo "❌ ADB not found. Install with: sudo apt install adb"; \
		exit 1; \
	fi
	@if ! adb devices | grep -q "device$$"; then \
		echo "❌ No device connected. Connect via USB and enable debugging."; \
		exit 1; \
	fi
	adb install -r btproxy-debug.apk
	@echo "✅ Docker-built app deployed successfully!"

# Show help
.PHONY: help
help:
	@echo "BT Proxy Build Commands:"
	@echo ""
	@echo "🐳 Docker builds (no Android SDK required):"
	@echo "  make docker-build   - Build APK using Docker"
	@echo "  make docker-deploy  - Build and install using Docker"
	@echo ""
	@echo "🔧 Local builds (requires Android SDK):"
	@echo "  make build     - Build debug APK"
	@echo "  make release   - Build release APK"
	@echo "  make deploy    - Build and install to USB device"
	@echo "  make install   - Install existing APK to device"
	@echo ""
	@echo "🧹 Maintenance:"
	@echo "  make clean     - Clean build artifacts"
	@echo "  make uninstall - Remove app from device"
	@echo "  make devices   - Show connected ADB devices"
	@echo "  make info      - Show APK information"
	@echo ""
	@echo "⚡ Quick start (no SDK required):"
	@echo "  1. Install Docker"
	@echo "  2. Connect Android device via USB"  
	@echo "  3. Enable USB debugging"
	@echo "  4. Run: make deploy"


git-push:
	@git add .
	@git commit -m "update"
	git push