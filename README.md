# VR Browser (SurfaceTexture / VirtualDisplay pipeline)

Replaces PixelCopy with a GPU-only path:

WebView -> VirtualDisplay -> Surface -> SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) -> drawn once per eye viewport

## Open in Android Studio
1. Open Android Studio -> Open -> select the `VRBrowser` folder.
2. Let it sync (it will fetch/generate the Gradle wrapper automatically;
   if prompted, allow Android Studio to download Gradle).
3. Run on a physical device (SurfaceTexture/external OES textures don't
   behave reliably on most emulators).

## Where to change things
- URL: `MainActivity.kt`, in `attachWebView()` -> `loadUrl(...)`.
- Off-screen WebView resolution: `StereoRenderer.BROWSER_WIDTH` / `BROWSER_HEIGHT`.

## Note
`DisplayManager.createVirtualDisplay()` with `VIRTUAL_DISPLAY_FLAG_PUBLIC |
VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` does not require a runtime
permission for this off-screen-rendering use case (it isn't mirroring the
real device display). If a specific OEM/Android version throws a
SecurityException here, that's the first thing to check.

## Scope
This is only the rendering pipeline fix (no more PixelCopy). It does not
include lens distortion, head tracking, or a VR SDK (Cardboard/OpenXR) -
add one of those on top once this base is working.
