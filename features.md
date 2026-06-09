# 🌟 AuraOrbit: Comprehensive Feature Guide

AuraOrbit is packed with advanced features that bridge standard Android UI concepts with a high-performance 3D rendering pipeline. This guide covers every feature available in the app.

## 📱 Live Wallpaper Engine
The core of AuraOrbit is a fully interactive, GPU-accelerated live wallpaper.
- **True 3D Interactive Sphere:** Rotate your apps with natural quaternion-based momentum and exponential friction. No gimbal lock, ever.
- **Fibonacci Distribution:** Apps are automatically spaced evenly around the 3D sphere using the golden angle formula (`φ = π(3 - √5)`), creating a perfectly uniform lattice regardless of how many apps you select.
- **120Hz Display Unlock:** Designed specifically for flagship displays, AuraOrbit explicitly unlocks your hardware's 120 FPS limit to deliver a buttery-smooth physics experience.
- **Page Isolation:** The sphere seamlessly fades in and out as you swipe across your launcher pages, scaling perfectly with your wallpaper offsets.

## 🎨 Intelligent App Grouping
Organize your apps exactly how you want them.
- **Spatial Clustering:** Group related apps together (e.g. "Social", "Games", "Work"). Apps within the same group are spatially clustered closely together on the sphere.
- **Translucent Colored Backdrops:** Each group features a custom-colored, 3D curved polygon backdrop that orbits *behind* the icons. It uses `IntAttribute.CullFace=GL_NONE` to remain visible even when the group is on the far side of the sphere.

## 🚀 Sphere Mode (Standalone App Launcher)
Don't want it as a wallpaper? Run it as a standalone app.
- **Fullscreen Exclusive Mode:** Launch `SphereModeActivity` directly from your app drawer. This gives you an immersive, edge-to-edge 3D sphere that owns all touch input—no gesture conflicts with your system launcher.
- **Instant Loading:** App icons and bitmaps are cached in memory. When you launch Sphere Mode, the engine skips the heavy `PackageManager` rasterization process and loads instantly.

## 🧩 Dynamic Group Widgets
Bring specific groups directly to your home screen.
- **Pin Groups as Widgets:** Long-press the app icon to pin specific groups as customizable Android widgets.
- **Themed Widget Designs:** Widgets feature a sleek dark background with an orbiting ring that perfectly matches the color you assigned to the group.
- **Safety State (Deleted Groups):** If you delete a group from settings, its pinned widgets instantly update across your home screen to show a red "**Deleted**" label and disable clicking, signaling you to safely remove them.

## ⚙️ Extensive Configuration & Settings
AuraOrbit provides a robust, fully code-driven settings dashboard.
- **Custom Backgrounds:** Choose a personal photo via the Android Photo Picker. The background is applied underneath the 3D scene. 
- **System Wallpaper Passthrough:** If no custom photo is chosen, the engine can pull your system's static wallpaper and render it in the background, making the 3D sphere look like a translucent overlay.
- **Engine Tuning:** Customize sphere radius, icon scaling, background visibility, and target framerate directly from the app settings.

## 🏗️ Technical Highlights
- **Direct App Launching:** Uses accurate 3D raycasting (`Camera.getPickRay()`) directly on the 3D canvas to launch apps when tapped.
- **libGDX Integration:** Uses `DecalBatch` for ultra-fast 3D billboarding and `ModelBatch` for the translucent group backdrops.
- **No Storage Permissions Needed:** Background images use modern `ACTION_PICK_IMAGES` URI grants.
