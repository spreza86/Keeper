# Keeper

Android app that suggests which photos to delete from a Sony RAW folder, and groups the rest by trip, place, color and light. Everything runs on the phone. The app has no internet permission.

## Build and install

1. Unzip the folder and open it in Android Studio (File > Open, pick the `Keeper` folder).
2. Let Gradle sync. Android Studio may offer to upgrade the Android Gradle plugin or Kotlin: either accept or skip, both work.
3. Plug in the phone with USB debugging on, pick it in the device list and press Run.
4. On first launch, allow access to photos and choose **Allow all**.

The first scan reads every RAW file once (a few minutes for ~9,000 photos). Later scans only look at new or changed photos.

## What's new in 0.5

- Photos no longer turn sideways in the full-screen viewer, the Tinder card and Bursts: the large preview now follows the camera's orientation tag.
- New Home: a photo hero with what's ready to free (Show me, Play Tinder mode), then an overview with a storage bar (Sets vs Bursts), four tiles (Abandoned sets, Bursts, Tinder mode, Waiting), a weekly "Freed with Keeper" chart and what Keeper learned from your edits.

## What's new in 0.4

- Tinder mode is its own tab. Settings moved to the gear on Home.
- One outing = one day out. Groups split by pauses are merged when they fall on the same day; a lone photo is never listed as a set.
- Full-screen viewer: hold any photo (or tap the big photo in Bursts and Tinder mode). Pinch or double-tap to zoom, swipe through the scene, keep or delete from there.
- "Compare" on scenes marked too close to call: both frames stacked, zooming together.

## What's new in 0.3

- "Freed with Keeper" card on Home: total photos and space freed, this month, and by Sets / Bursts / Quick clean. Stored only on the phone; undo subtracts.

## What's new in 0.2

- Material look with light and dark modes. Follows the phone by default; change it in Settings > Appearance.
- Outings show scene by scene, each with a "Keep all" or "Keep best" button. Single shots are grouped at the end.
- Quick clean: a short swipe round from the Home screen. Only the big photo on top is decided; similar shots from its group show underneath. One Android trash prompt at the end of the round.

## Defaults

| Setting | Default | Where to change |
|---|---|---|
| RAW folder | `A6700/` | Settings > Folders |
| Edits folder | `Pictures/AdobeLightroom/` | Settings > Folders |
| New outing after | 30 min without a shot | Settings > Rules |
| Abandoned after | 3 weeks with no edits | Settings > Rules |

For a friend's phone, pick their folders in Settings. Any Sony body that writes ARW works (A6700, A6400 and others).

## How it decides

- **Preview, not RAW**: each ARW carries a JPEG preview. Keeper reads that (`analysis/ArwPreview.kt`).
- **Scenes**: consecutive frames within 2 minutes whose blurred 24x24 fingerprints correlate above 0.65 (`logic/Organizer.kt`, `splitScenes`).
- **Scores** (`analysis/Analyzer.kt`): sharpness on the strongest edges (on faces when there are faces), eyes open and smile from on-device face detection, near-black and blown-out share, flash fired.
- **Calls** (`Organizer.decide`): anything edited stays. Frames that are blurry, eyes closed, flash not ready or too bright drop out. The best remaining frame is kept. If the top two are within 5% sharpness, both are marked *You choose*. Another good frame that looks clearly different is kept as *Different moment*. A set where nothing passes can end up with zero keepers.
- **Learning** (`logic/Learner.kt`): in scenes where you edited exactly one frame, the weights are nudged so that frame would win.
- **Places**: GPS in the photo first, then phone photos at the same time (15 min, same outing, same day), then neighbouring days of the same trip. Names come from `assets/cities.csv` and `assets/areas.csv` (GeoNames, offline).
- **Deleting**: through Android's own trash prompt. Restorable for 30 days, and there's an Undo.

## Files

```
app/src/main/java/com/preza/keeper/
  MainActivity.kt          trash prompt + app entry
  analysis/ArwPreview.kt   pulls the JPEG preview out of ARW files
  analysis/Analyzer.kt     sharpness, exposure, color, faces, fingerprint
  data/                    MediaStore access, cache, settings, offline geo, thumbnails
  logic/Organizer.kt       trips, outings, scenes, keep/delete, places, light
  logic/Learner.kt         learns from your edits
  logic/Holidays.kt        holiday tags
  ui/                      Compose screens
```
