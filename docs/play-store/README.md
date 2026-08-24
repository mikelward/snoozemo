# Play Store graphic assets

Store graphics for the `app.snoozemo` listing. Play Console has no API upload
path wired for these (see `docs/play-store-internal-track.md` — CI uploads the
AAB only), so they reach the listing by hand: Grow → Store presence → Main store
listing.

| File | Play slot | Spec | This file |
| --- | --- | --- | --- |
| `icon-512.png` | App icon | PNG or JPEG, up to 1 MB, 512 × 512 px | 512×512, opaque, 10 KB |
| `feature-graphic.png` | Feature graphic | PNG or JPEG, up to 15 MB, 1,024 × 500 px | 1024×500, 75 KB |

Both are version-controlled for the same reason listing copy should be — so a
change to the store's front door is reviewable in a PR.

## Regenerating

```sh
./gradlew :app:testPlayDebugUnitTest \
  --tests "app.snoozemo.ui.PlayStoreGraphicsScreenshotTest" -Proborazzi.test.record=true
```

CI regenerates them on every PR that touches the app and **fails the build if
the committed files are stale**, so a forgotten re-render is caught rather than
shipped. It does not commit the refresh for you, unlike the UI snapshots:
`sync-screenshots` delegates to `mikelward/ci-commit-artifact`, which commits one
artifact into one destination, and widening that is a change to a deliberately
narrow fork-safe mechanism shared with other repos. So: change the drawable, run
the command above, commit both.

## Android draws these, not a renderer of ours

`app/src/test/kotlin/app/snoozemo/ui/PlayStoreGraphicsScreenshotTest.kt` loads
`ic_launcher` through the resource system and draws its `background` and
`foreground` layers with `Drawable.draw(Canvas)`, in a Robolectric test running
`GraphicsMode.NATIVE` — which is Skia, the same rasterizer that draws the icon on
a device. The store icon therefore cannot disagree with the launcher icon about
how the artwork is *drawn* — shapes, strokes, color — because it is not an
interpretation of the same file, it is the same drawing code. Taking the layers from `ic_launcher` itself rather than from the drawables
it names today also means repointing the adaptive icon carries these with it.

That is the point of the design, and it was learned twice.

**The first attempt transcribed the mark into HTML** and screenshotted it with
headless Chromium. The copy then had to be policed, and policing it grew
allowlists for path attributes, group and root attributes, CSS selectors and CSS
properties, plus rules for quoting, whitespace, comments, multiline tags,
uppercase tags, `!important`, and non-matching media queries — nine rounds of
review, each one finding another way for a source file to say something the
checks didn't read.

**The second removed the copy and wrote a renderer** that parsed the drawable and
rasterized it with Pillow. That moved the problem one level down and it failed
the same way: **ten rounds of review, every finding genuine, and every one of
them a piece of 2D rasterization it had got wrong** — fill rules, stroke caps and
joins, closed-contour seams, curve-flattening tolerance, gradient projection.
Measured against Android on the same drawable it still differed in 1.8% of the
icon's pixels, by up to 107/255.

The general lesson, worth keeping: reading a file is not the same as knowing what
a browser will draw from it, and writing a renderer is not the same as knowing
whether it matches Android — *that* is not a question a renderer can answer about
itself, and no amount of review closes the gap. The platform's own renderer runs
in a JVM unit test and costs no new dependency; Robolectric and Roborazzi were
already here for the UI snapshots.

What that deletes is as telling as what it adds. There is no path parser, no
grammar allowlist, no cap/join handling, no bounds computation, no gradient ramp,
no `colors.xml` parsing, no check that the adaptive icon still points where the
renderer looks — and no list of constructs refused because they could not be
drawn faithfully. A curve, a `<clip-path>`, an `evenOdd` cut-out and a nested
gradient all simply work, because Android supports them; the old renderer refused
every one. The mark's painted extent, which the feature graphic crops to, is
*measured* by rendering the layer and finding the non-transparent pixels rather
than predicted from the geometry — and measured over the launcher-visible region
only, the same crop the icon takes, so artwork in the bleed cannot reach the
feature graphic when no launcher would show it.

## App icon

`icon-512.png` is the adaptive launcher icon's two layers, **cropped to the 72dp
a launcher shows**. An adaptive icon's layers are a 108dp canvas of which only
the central 72dp is ever visible, so a launcher draws them at 108/72 and keeps
the middle; this does the same, and the mark is therefore the size a person sees
on their home screen. Rendering the whole canvas instead made the mark read a
third smaller in the store than in the app — the comparison someone actually
makes when the listing sits beside the installed icon. typelauncher's renderer
crops for the same reason.

Play applies its own rounded-corner mask, so the file is still a full opaque
square: no transparency and no pre-rounded corners. The layers are drawn
separately rather than by drawing the `AdaptiveIconDrawable`, which would bake in
a device mask this must not carry. The test asserts the opacity, since Play
rejects an icon with an alpha channel that isn't full.

`LauncherIconScreenshotTest` is the separate guard on the same mark inside the
app: it renders the adaptive icon and its `monochrome` layer the way a launcher
and a themed launcher do. That test and this one answer different questions — it
asks whether the mark survives the mask, this asks what the store shows — so both
stay.

## Feature graphic

The mark on the left, `Snoozemo` and a two-line tagline on the right, over a
diagonal gradient. Type is Roboto, from the same Android that draws everything
else here; the gradient is a real `LinearGradient` at 135°.

Copy is `README.md`'s line, which is the closest thing to approved product copy
this repo has: the listing text itself is not under version control yet
(`docs/play-store-internal-track.md`, "Store listing"). When the fastlane
metadata lands, this graphic follows its short description instead, and changing
the wording here means changing it there too — new store copy needs the usual
chat approval first (`AGENTS.md`, *Translations*).

Keep the artwork and text inside the middle of the canvas. Play crops the
feature graphic on some surfaces and overlays a play button on it when the
listing has a promo video, so anything near an edge is at risk — the test refuses
a composition that would be cropped rather than centering it off the canvas.
