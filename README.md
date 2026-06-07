# AuraOrbit 🌍✨

**AuraOrbit** is a next-generation Android Live Wallpaper featuring a fully interactive 3D sphere that orbits your favorite apps right on your home screen. Built on the high-performance libGDX game engine, it leverages a golden angle Fibonacci distribution to plot your apps perfectly in a 3D space, supporting true 120 FPS hardware refresh rates!

![AuraOrbit Logo](app/src/main/res/drawable/ic_launcher_foreground.png)

## 🚀 Features

- **True 3D Interactive Sphere**: Rotate your apps with natural quaternion-based momentum and exponential friction. No gimbal lock, ever.
- **120Hz Display Unlock**: Designed specifically for flagship displays (like the Galaxy S25 Ultra), AuraOrbit explicitly unlocks your hardware's 120 FPS limit to deliver a buttery-smooth physics experience.
- **Fibonacci Distribution**: Apps are automatically spaced evenly around the 3D sphere using the golden angle formula (`φ = π(3 - √5)`).
- **Intelligent Grouping & Backdrops**: Group related apps together (e.g. "Social", "Games", "Work"). Apps are spatially clustered on the sphere, and groups feature translucent, 3D curved colored backdrops that orbit *behind* the icons.
- **Direct App Launching**: Uses accurate 3D raycasting (`Camera.getPickRay()`) directly on the 3D canvas so you can launch your apps simply by tapping them on the sphere.
- **Page Isolation**: The sphere seamlessly fades in and out as you swipe across your launcher pages, scaling perfectly with your wallpaper offsets.
- **Retains Your System Wallpaper**: Peeks at your existing system wallpaper and renders it directly on the canvas behind the 3D scene.
- **Adaptive Icon Ready**: Ships with a stunning custom adaptive icon tailored for modern Android launchers.

## 🛠️ Architecture

AuraOrbit seamlessly merges standard Android UI with a high-performance C++ OpenGL backend via libGDX:
- **`MyWallpaperService.java`**: The Android bridge handling the OS lifecycle, 120Hz unlock (`Surface.setFrameRate`), and launcher scroll offsets.
- **`SphereEngine.java`**: The core 3D engine running libGDX (`ApplicationListener`). Uses `DecalBatch` for ultra-fast 3D billboarding and `ModelBatch` for the translucent group backdrops.
- **`LiveWallpaperSettings.java`**: A purely code-driven Settings Activity (using `PreferenceFragmentCompat` and programmatically generated dialogs) to curate your apps and build custom-colored groups.
- **`AppFetcher.java`**: The data pipeline directly interfacing with Android's `PackageManager` to pull high-res application icons and convert them safely into OpenGL textures on the fly.

## 📦 How to Build & Install

The codebase requires Java 17 (due to AGP 8.x) to build correctly. 

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
