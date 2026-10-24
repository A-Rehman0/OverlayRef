# Overlay Ref (Android)

A transparent reference image floats over any app (your camera app, for example).
Touches pass straight through it. A small draggable ball controls it.

## Build the APK in the cloud (nothing to install)

1. Make a free account on github.com and create a new **private** repository.
2. Add all files of this project, keeping the folder layout:
   - On a PC: drag the whole project folder into "Add file > Upload files".
   - On a phone: use "Add file > Create new file" and type the full path as the name
     (typing `/` makes folders), for example
     `app/src/main/java/com/example/overlayref/MainActivity.java`, then paste the code.
   - Do not forget `.github/workflows/build.yml`.
3. Open the **Actions** tab. The "Build APK" job starts by itself (about 3-5 minutes).
4. Open the finished run, scroll to **Artifacts**, download `OverlayRef-apk`, unzip it.
5. Install `app-debug.apk` on your phone (allow "install unknown apps" if asked).

If the build shows a red cross, open the log, copy the error text and send it to me.

## Using the app

1. Open **Overlay Ref**.
2. Tap **1** and allow "Display over other apps".
3. Tap **2** and choose the folder with your reference images.
4. Tap **3 Start overlay**, then open your camera app.

The ball:
- **Tap**: open the panel (opacity, size, previous / next image, overlay on/off, stop)
- **Long-press**: switch the overlay on/off quickly (ball turns red when off)
- **Drag**: move it; it snaps to the nearest screen edge

Images are sorted naturally (img2 before img10). The app remembers the last image,
opacity, size and ball position.

## Limits

- Android only (iPhone does not allow overlays over other apps).
- Opacity is capped at 80%: Android 12+ blocks taps passing through more opaque overlays.
- Some phones (Xiaomi, Samsung, etc.) kill background apps. If the overlay disappears,
  set battery use for this app to "Unrestricted".
- Banking and other secure screens hide overlays.
- The camera app saves the photo, so the overlay is never inside it.
