# 🌟 AuraOrbit: Comprehensive Feature Guide (v3.0.0)

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

## 🎨 Full-Screen Blur & Visual Effects (v3.0.0)
- **Automatic Full-Screen Blur:** The background blur radius expands automatically across the full screen dimensions, ensuring smooth visual depth.
- **Blur Strength Control:** Granular control over blur intensity (0% to 100%) with instant real-time preview in settings and widgets.

## 🧩 Independent Widget Spheres & Widget Edit UX (v3.0.0)
Bring custom 3D app spheres directly to your home screen as widgets.
- **Independent Configurations:** Each widget maintains its own app selection, sphere position, scale, blur strength, icon size, orbit color, and custom logo independent of the Permanent Sphere.
- **Unified Multi-Layer Icons:** Widget list rows render the exact multi-layered icon (Planet + Color-Tinted Ring + Custom Logo) matching home screen widgets.
- **Modern Widget Editing UI:**
  - **"Apps in Widget" Row:** Displays dynamic selection counts (e.g. "12 apps selected") with a dedicated app selection dialog.
  - **Integrated "Add to Home Screen" Pin Button:** Tonal Material 3 button positioned inside the Widget Preview card.
  - **Sticky Action Bar:** Clean full-width "Save Widget" button in Create Mode and outlined red "Delete Widget" button with confirmation in Edit Mode.

## 🛡️ Smart Gesture Handling & Launcher Compatibility (v3.0.0)
- **Drag vs. Tap Differentiation:** `TouchOverlayView` separates drag gestures (> 16px) from quick taps (< 16px). Dragging rotates the 3D sphere smoothly without accidental launcher page swipes, while quick taps pass cleanly through to 2D apps in the App Drawer or Recent Apps list.
- **Samsung One UI & OEM Compatibility:** Optimized page-distance calculations for offset-silent launchers like Samsung One UI, keeping the sphere strictly visible on the selected active page.
- **Launcher State Accessibility Integration:** Optional accessibility service using `flagReportViewIds` and `flagIncludeNotImportantViews` to detect app drawer and recent apps states across OEM launchers.
- **Launcher Compatibility Notice:** Clear, non-intrusive warning card at the top of the Permanent Sphere setup page notifying users of launcher-specific OS restrictions.

## 🚀 Sphere Mode (Standalone App Launcher)
Don't want it as a wallpaper? Run it as a standalone app.
- **Fullscreen Exclusive Mode:** Launch `SphereModeActivity` directly from your app drawer or widget. This gives you an immersive, edge-to-edge 3D sphere that floats perfectly over your home screen.
- **Instant Loading:** App icons and bitmaps are cached in memory. When you launch Sphere Mode, the engine skips the heavy `PackageManager` rasterization process and loads instantly.

## ⚙️ Configuration & Dashboard
- **App Version Display:** Home dashboard displays the active app version (`v3.0.0`).
- **Engine Tuning:** Customize icon sizes and target framerate (30/60/90/120 FPS) directly from the app settings.
- **Direct App Launching:** Uses accurate 3D raycasting (`Camera.getPickRay()`) directly on the 3D canvas to launch apps when tapped.
- **No Storage Permissions Needed:** Background images use modern `ACTION_PICK_IMAGES` URI grants.
