# 🌟 AuraOrbit: Comprehensive Feature Guide

AuraOrbit is packed with advanced features that bridge standard Android UI concepts with a high-performance 3D rendering pipeline. This guide covers every feature available in the app.

## 📱 Live Wallpaper Engine
The core of AuraOrbit is a fully interactive, GPU-accelerated live wallpaper.
- **True 3D Interactive Sphere:** Rotate your apps with natural quaternion-based momentum and exponential friction. No gimbal lock, ever.
- **Fibonacci Distribution:** Apps are automatically spaced evenly around the 3D sphere using the golden angle formula (`φ = π(3 - √5)`), creating a perfectly uniform lattice regardless of how many apps you select. The sphere maintains its full radius regardless of icon size!
- **120Hz Display Unlock:** Designed specifically for flagship displays, AuraOrbit explicitly unlocks your hardware's 120 FPS limit to deliver a buttery-smooth physics experience.
- **Page Isolation:** The sphere seamlessly fades in and out as you swipe across your launcher pages, scaling perfectly with your wallpaper offsets.

## 🎨 Icon Pack Support & Theming
Comprehensively theme your 3D experience with third-party icon packs.
- **Seamless Icon Pack Integration:** Apply any popular icon pack (Nova, Apex, Arcticons, etc.) directly from the dashboard.
- **3D Sphere Theming:** All apps orbiting your sphere are instantly updated with their themed variants.
- **Widget & App Picker Theming:** Icon packs are applied across the entire app ecosystem—from the "Select Apps" picker to the logos on your home screen widgets!
- **Intelligent Fallbacks:** If an app isn't natively supported by the icon pack (such as AuraOrbit itself), the engine intelligently applies generic drawer/launcher icons from the pack to maintain a cohesive look.

## 🎨 Intelligent App Grouping & Customization
Organize your apps exactly how you want them, with powerful per-group customization options.
- **Spatial Clustering:** Group related apps together (e.g. "Social", "Games", "Work"). Apps within the same group are spatially clustered closely together on the sphere.
- **Translucent Colored Backdrops:** Each group features a custom-colored, 3D curved polygon backdrop that orbits *behind* the icons. It uses `IntAttribute.CullFace=GL_NONE` to remain visible even when the group is on the far side of the sphere.
- **Individual Group Overrides:** You can now customize settings for *specific groups* independently of the global settings!
  - **Custom Sphere Position & Scale:** Drag, drop, and resize the sphere for a specific group.
  - **Custom Background Blur:** Set unique blur radius and strength per group.
  - **Custom Background Image:** Assign a unique background image to show only when a specific group is active.
- **Auto-Save Functionality:** Changes made in the group editor are seamlessly auto-saved when you navigate away, with live previews updating your widgets instantly.
- **Seamless Edit UX:** When editing an existing group, manage the apps list effortlessly via a clean popup dialog.

## 🚀 Sphere Mode (Standalone App Launcher)
Don't want it as a wallpaper? Run it as a standalone app.
- **Fullscreen Exclusive Mode:** Launch `SphereModeActivity` directly from your app drawer or widget. This gives you an immersive, edge-to-edge 3D sphere that floats perfectly over your home screen (no more black dimming overlay!).
- **Instant Loading:** App icons and bitmaps are cached in memory. When you launch Sphere Mode, the engine skips the heavy `PackageManager` rasterization process and loads instantly.

## 🧩 Dynamic Group Widgets
Bring specific groups directly to your home screen.
- **Pin Groups as Widgets:** Pin specific groups as customizable Android widgets.
- **Themed Widget Designs:** Widgets feature a sleek dark background with an orbiting ring that perfectly matches the color you assigned to the group.
- **Granular Widget Customization:** Click the gear icon on any widget to adjust its appearance—upload custom logos, hide the text label, toggle transparency, or enforce system Material You colors!
- **Safety State (Deleted Groups):** If you delete a group from settings, its pinned widgets instantly update across your home screen to show a red "**Deleted**" label and disable clicking.

## ⚙️ Extensive Configuration & Settings
AuraOrbit provides a robust, fully code-driven settings dashboard.
- **Custom Backgrounds:** Choose a personal photo via the Android Photo Picker. 
- **System Wallpaper Passthrough:** If no custom photo is chosen, the engine can pull your system's static wallpaper and render it in the background.
- **Engine Tuning:** Customize icon sizes and target framerate (30/60/90/120 FPS) directly from the app settings. Sphere scaling acts as the radius control.

## 🏗️ Technical Highlights
- **Direct App Launching:** Uses accurate 3D raycasting (`Camera.getPickRay()`) directly on the 3D canvas to launch apps when tapped.
- **libGDX Integration:** Uses `DecalBatch` for ultra-fast 3D billboarding and `ModelBatch` for the translucent group backdrops.
- **No Storage Permissions Needed:** Background images use modern `ACTION_PICK_IMAGES` URI grants.
