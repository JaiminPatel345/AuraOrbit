# AuraOrbit 🌍✨

**AuraOrbit** is a next-generation Android Live Wallpaper featuring a fully interactive 3D sphere that orbits your favorite apps right on your home screen. Built on the high-performance libGDX game engine, it leverages a golden angle Fibonacci distribution to plot your apps perfectly in a 3D space, supporting true 120 FPS hardware refresh rates!
![AuraOrbit Logo](assets/logo.svg)

## 🚀 Core Features

- **3D Interactive Sphere**: Rotate your apps with natural quaternion-based momentum at 120 FPS.
- **Intelligent Grouping**: Group related apps together with 3D translucent, color-coded backdrops.
- **Dynamic Widgets**: Pin customized, group-specific dynamic widgets straight to your home screen.
- **Standalone Sphere Mode**: Launch AuraOrbit as a standalone, fullscreen immersive app.
- **Instant Performance**: Advanced memory caching guarantees your sphere loads instantly without delay.

📖 **Want to see everything AuraOrbit can do? Check out the [Comprehensive Features Guide](features.md).**

## 🛠️ Architecture

AuraOrbit seamlessly merges standard Android UI with a high-performance C++ OpenGL backend via libGDX:
- **`MyWallpaperService.java`**: The Android bridge handling the OS lifecycle, 120Hz unlock (`Surface.setFrameRate`), and launcher scroll offsets.
- **`SphereEngine.java`**: The core 3D engine running libGDX (`ApplicationListener`). Uses `DecalBatch` for ultra-fast 3D billboarding and `ModelBatch` for the translucent group backdrops.
- **`LiveWallpaperSettings.java`**: A purely code-driven Settings Activity (using `PreferenceFragmentCompat` and programmatically generated dialogs) to curate your apps and build custom-colored groups.
- **`AppFetcher.java`**: The data pipeline directly interfacing with Android's `PackageManager` to pull high-res application icons and convert them safely into OpenGL textures on the fly.

## 📦 How to Build & Install

**AuraOrbit will be available on the Google Play Store very soon!**

In the meantime, you can download the latest compiled APK directly from this repository:
👉 **[Download AuraOrbit.apk](apk/AuraOrbit.apk)**

If you prefer to build it from source, the codebase requires **Java 17+** (due to AGP 8.x) to build correctly. Newer versions like Java 21 work perfectly as well. 

**Via Android Studio (Recommended)**
1. Open the project folder in Android Studio.
2. Connect your device (ensure USB Debugging is on).
3. Click the green ▶️ **Run** button.

**Via Command Line (Mac/Linux)**
```bash
# Ensure Java 17 is active in your terminal environment
export JAVA_HOME=/path/to/java/17
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
*Note: If you have a running emulator or connected device, you can use `./gradlew installDebug` to build and deploy in a single step.*

## ⚙️ Configuration

Once installed, navigate to:
**Settings → Wallpaper & style → Change wallpapers → Live wallpapers → AuraOrbit**. 

Hit the **Settings ⚙️** icon in the preview window to open the AuraOrbit dashboard where you can:
- Select which apps appear on your orbit.
- Create color-coded app groups.
- Set the target framerate (60/90/120).
- Toggle your underlying system background.
- Adjust sphere radius and icon scaling!

## 📄 License

This project is licensed under a custom **Non-Commercial License**. You are free to use, copy, and modify the software for personal and non-commercial purposes. Commercial use, distribution, or monetization is strictly prohibited without explicit prior written permission from the author. 

For more details, please read the [LICENSE](LICENSE) file.
