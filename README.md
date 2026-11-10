# Keeper

Android app that suggests which photos to delete from a Sony RAW folder, and groups the rest by trip, place, color and light. Everything runs on the phone. The app has no internet permission.

## Build and install

1. Unzip the folder and open it in Android Studio (File > Open, pick the `Keeper` folder).
2. Let Gradle sync. Android Studio may offer to upgrade the Android Gradle plugin or Kotlin: either accept or skip, both work.
3. Plug in the phone with USB debugging on, pick it in the device list and press Run.
4. On first launch, allow access to photos and choose **Allow all**.

The first scan reads every RAW file once (a few minutes for ~9,000 photos). Later scans only look at new or changed photos.

## What's new in 0.7
- **Delete part of a set.** Each scene (and the Single shots block) has a Done check. Once you tick any, the bottom button deletes only from the ticked scenes; the rest of the set waits for next time. "Keep all" and "Keep best" tick the scene too.
- **Your choices are saved.** When you press the delete (or done) button in Sets or Bursts, your keep and delete choices are saved on the phone. Reviewed scenes are hidden next time, a set leaves the list once every scene is reviewed, and photos you kept show "Kept by you". Settings has "Forget my choices". Tinder swipes are still not saved.
- **Sort Sets and Bursts:** Random (default), Newest, Oldest or Most to free. The choice is remembered. Random gives a new order each time the app opens. Bursts keep your place when you leave the tab and come back.
- **"Eyes closed" no longer suggests a deletion.** Eye detection misreads narrower eyes and people looking down, so low eye scores only lower the ranking. "Why?" shows the score.
- **Learning from your corrections.** Each time you keep or delete a photo a check flagged (Blurry, Too bright, Flash not ready, eyes), the answer is recorded, Tinder swipes included. After 10 answers where you kept 7 or more, that check gets one step less strict (3 steps at most); confirming most of them again moves it back. Settings and "Why?" show what changed.
- **Colour themes** in Settings: Match my wallpaper (Android 12+), Olive, Ocean, Terracotta, Plum, Graphite. Keep stays green and delete red in every theme.
- **New app icon:** two photo frames, the front one checked. Includes a single-colour version for Android themed icons.

## What's new in 0.6
- A scene always keeps at least one photo. If every frame has a problem, the best-scoring one stays as "Best of a weak set". (A lone photo with a problem is still suggested for deletion.)
- Scenes are grouped by time first: frames under 5 seconds apart are always one scene. A frame joins a scene if it looks like any recent frame of it, with bright spots (lamps, flash) capped so one light can't split a burst. A lone frame joins a similar scene within 30 seconds. "Only shot of this scene" now means it.
- "Why?" in the full-screen viewer (hold a photo): the grouping, sharpness, eyes, flash and rule behind each label, with the thresholds.
- Viewer title names the group, e.g. "Scene 2 · 1 of 3" or "Single shots · 1 of 4".
- Tinder mode: a new random order every round, at most 3 photos per outing so a round mixes outings.
- Home: a random photo from the whole library, new each time the app opens (never the same as last time, never a near-black frame), with its date and place. Tap it to open that outing; "Play Tinder mode" starts the round with it.

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
- **Scenes** (`logic/Organizer.kt`, `splitScenes`): frames under 5 s apart are always one scene (burst). Otherwise, within 2 minutes, a frame joins the scene if its blurred 24x24 fingerprint correlates at least 0.65 with any of the scene's last 12 frames (bright spots capped, `Features.robustSimilarity`). Then a lone frame joins a neighbouring scene within 30 s if it correlates at least 0.5.
- **Scores** (`analysis/Analyzer.kt`): sharpness on the strongest edges (on faces when there are faces), eyes open and smile from on-device face detection, near-black and blown-out share, flash fired.
- **Calls** (`Organizer.decide`): anything edited stays. Frames that are blurry, flash not ready or too bright drop out (limits loosened by `logic/Checks.kt` from your corrections). Low eyes-open scores only lower the ranking. The best remaining frame is kept. If the top two are within 5% sharpness, both are marked *You choose*. Another good frame that looks clearly different is kept as *Different moment*. If nothing passes in a scene of 2 or more, the best-scoring frame is kept as *Best of a weak set*. Each decision carries a plain-language *why* shown in the viewer.
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
