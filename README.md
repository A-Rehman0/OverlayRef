# Overlay Ref (Android)

A transparent reference image floats over any app (your camera app, for example).
Touches pass straight through it. A small draggable ball controls it.
Your reference images are built into the app, so you never pick a folder.

## Adding your reference images

Put them in this repository folder (create it with "Add file > Upload files"):

    app/src/main/assets/references/

- jpg, jpeg, png, webp are supported. Names are sorted naturally (img2 before img10).
- Shrink them first (about 1280 px on the long side is plenty). Smaller images = faster app.
- GitHub's web upload takes about 100 files at a time, so upload in batches.
- Every commit rebuilds the APK automatically (Actions tab, 3-5 minutes).

## Build the APK in the cloud

1. Push / upload the project to a private GitHub repository.
2. Open the **Actions** tab, open the latest "Build APK" run.
3. Download **OverlayRef-apk** under Artifacts, unzip, install `app-debug.apk`.
   Installing over the old version keeps your settings.

## Using the app

1. Open **Overlay Ref**, tap **1** and allow "Display over other apps" (once).
2. Tap **2 Start overlay**, then open your camera app.

The ball:
- **Tap**: control panel
- **Long-press**: overlay on / off (ball turns red when off)
- **Drag**: move it; it snaps to the nearest screen edge

The panel:
- **Reference image** button: opens a searchable list like Streamlit's selectbox
  (type part of a name, tap an image; the current one is highlighted green)
- **Opacity** 5% to 100%
- **Size** 50% to 200%
- **Prev / Next**, **Overlay ON/OFF**, **Stop app**

## Limits

- Android only (iPhone does not allow overlays over other apps).
- Some phones kill background apps. If the overlay disappears, set battery use
  for this app to "Unrestricted".
- Banking and other secure screens hide overlays.
- The camera app saves the photo, so the overlay is never inside it.
