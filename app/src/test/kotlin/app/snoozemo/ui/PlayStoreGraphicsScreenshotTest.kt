package app.snoozemo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The two Play Store graphics, drawn by Android rather than by a renderer of
 * our own.
 *
 * These are deliverables, not snapshots of a screen — they are what the store
 * listing shows — but they are produced here, in a Robolectric test with native
 * graphics, for one reason: **this is the same code path the launcher uses.**
 * `Drawable.draw(Canvas)` on a `VectorDrawable` under `GraphicsMode.NATIVE` is
 * Skia, which is what actually rasterizes the icon on a device. A store icon
 * that disagrees with the launcher icon is a review flag, and nothing else can
 * rule that out: an independent renderer can be reviewed to death and still
 * differ, because "does this match Android" is not a question it can answer
 * about itself. The Python renderer this replaces did differ, visibly, in the
 * mark — see the sibling change on simmo, where the two were measured against
 * each other.
 *
 * The layers come from `ic_launcher` itself rather than from the drawables it
 * happens to name today, so repointing the adaptive icon carries the store
 * graphics with it instead of leaving them rendering the abandoned layer.
 *
 * Recorded like any other screenshot — `-Proborazzi.test.record=true`. CI
 * re-records them on every PR that touches the app and **fails the build** if
 * they differ from what is committed, which is what keeps them from going stale
 * after a drawable edit. Unlike the UI snapshots it does *not* commit the
 * refresh for you: `sync-screenshots` delegates to `ci-commit-artifact`, which
 * commits one artifact into one destination, so a stale graphic has to be
 * re-recorded and committed by hand. Don't wait for a bot commit that cannot
 * arrive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStoreGraphicsScreenshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The app icon: the adaptive icon's two layers on their full 108dp canvas.
     *
     * Play applies its own rounded-corner mask, so this is a full opaque square
     * — no transparency and no pre-rounded corners — but it *is* cropped to the
     * 72dp a launcher shows, so the mark is the size a person sees on their home
     * screen. Drawn as separate layers rather than by drawing the
     * `AdaptiveIconDrawable`, which would bake in a device mask this must not
     * carry.
     */
    @Test
    fun `app icon`() {
        val bitmap = iconBitmap()
        assertFullyOpaque(bitmap)
        capture(bitmap, "icon-512.png")
    }

    /**
     * The two adaptive layers composed and cropped the way a launcher shows them.
     *
     * An adaptive icon's layers are a 108dp canvas of which only the central
     * 72dp is ever visible, so a launcher draws them at 108/72 and keeps the
     * middle. Rendering the whole canvas instead — which this did at first —
     * makes the mark read a third smaller in the store than on the home screen,
     * which is the comparison a person actually makes when the listing sits
     * beside the installed app.
     *
     * The mask itself is deliberately not applied: Play adds its own rounded
     * corners, so the file stays a full opaque square.
     */
    private fun iconBitmap(size: Int = 512): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bleed = bleedFor(size.toFloat()).roundToInt()
        val icon = adaptiveIcon()
        for (layer in listOfNotNull(icon.background, icon.foreground)) {
            layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            layer.draw(canvas)
        }
        return bitmap
    }

    /**
     * The feature graphic: the mark on the left, the wordmark and tagline on
     * the right, over a diagonal gradient.
     *
     * Play crops this on some surfaces and lays a play button over it when the
     * listing has a promo video, so the composition keeps well clear of the
     * edges.
     */
    @Test
    fun `feature graphic`() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = gradient() })

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = WORDMARK_SIZE
            letterSpacing = WORDMARK_TRACKING / WORDMARK_SIZE
            color = Color.WHITE
        }
        val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = TAGLINE_SIZE
            color = Color.parseColor("#B8C6E0")
        }

        val copyWidth = maxOf(
            bold.measureText(WORDMARK),
            TAGLINE.maxOf { regular.measureText(it) },
        )
        val leading = (TAGLINE_SIZE * TAGLINE_LEADING).roundToInt().toFloat()
        val total = ART_WIDTH + ART_GAP + copyWidth
        val block = WORDMARK_SIZE + COPY_GAP + leading * TAGLINE.size
        fits(total, WIDTH, "wide")
        fits(block, HEIGHT, "tall")

        val left = (WIDTH - total) / 2f
        drawMark(canvas, left)

        val copyLeft = left + ART_WIDTH + ART_GAP
        val top = (HEIGHT - block) / 2f
        canvas.drawText(WORDMARK, copyLeft, top + WORDMARK_SIZE, bold)
        var baseline = top + WORDMARK_SIZE + COPY_GAP + leading * FIRST_LINE
        for (line in TAGLINE) {
            canvas.drawText(line, copyLeft, baseline, regular)
            baseline += leading
        }
        capture(bitmap, "feature-graphic.png")
    }

    /**
     * Nothing outside this recording variant may redefine the launcher icon.
     *
     * These are recorded from `playDebug`, because that is the variant the
     * screenshot job runs; what ships is `playRelease`. Today those resolve the
     * same icon — only `src/main` declares `android:icon` and only `src/main`
     * carries the drawables — but nothing stops a later `src/release` or
     * `src/playRelease` from overriding either, and then the recording would
     * match the debug launcher while the store showed something else, with the
     * freshness check green throughout.
     *
     * Recording from the release variant instead would just move the blind spot
     * to a `src/debug` override, so this refuses the divergence rather than
     * picking a side: a build-type source set that redefines the icon fails
     * here, naming itself. The `direct` flavor is deliberately not checked — it
     * never reaches Play, so its icon is free to differ.
     *
     * "Redefines" has to include what the icon references, not just files named
     * after it: the background is `@color/ic_launcher_background`, so a
     * `res/values/colors.xml` redefining that name repaints the icon without any
     * file called `ic_launcher*` existing in the source set at all.
     */
    @Test
    fun `no build type redefines the launcher icon`() {
        val offenders = BUILD_TYPE_SOURCE_SETS
            .map { File("src/$it") }
            .filter { it.isDirectory }
            .filter { set -> redefinesTheIcon(set) }
        assertTrue(
            "These source sets redefine the launcher icon, so what this records from " +
                "playDebug is not what playRelease ships: $offenders. Either drop the " +
                "override, or record these graphics from the variant that ships.",
            offenders.isEmpty(),
        )
    }

    /**
     * The icon must not depend on the device configuration.
     *
     * The source-set guard above catches a *variant* redefining the icon, which
     * one test run cannot render both of. Configuration is the other axis and it
     * is testable directly: `values-night` already exists in this app, so a
     * `ic_launcher_background` added there would repaint the launcher in dark
     * mode while this recording — made in the default configuration — stayed
     * green.
     *
     * Rendering it and comparing measures the thing that matters rather than a
     * proxy for it — but only for the one qualifier it renders. The scan below
     * is what covers the rest, by refusing a qualified alternative outright
     * instead of trying to enumerate what to draw.
     */
    @Test
    fun `the icon is the same in night mode`() {
        val byDay = iconBitmap()
        RuntimeEnvironment.setQualifiers("+night")
        val byNight = iconBitmap()
        assertTrue(
            "The launcher icon renders differently in night mode, so the recorded store " +
                "icon is one of two possible launchers. Keep the icon's resources out of " +
                "configuration-qualified folders.",
            byDay.sameAs(byNight),
        )
    }

    /**
     * No configuration variant may redefine the launcher icon.
     *
     * The night test above renders one configuration and compares; every other
     * qualifier — orientation, locale, screen width — is invisible to it, and
     * rendering all of them is not a closed set. So this refuses the qualified
     * alternative instead: a `drawable-land/ic_launcher_foreground.xml`, or a
     * `values-night` redefining `ic_launcher_background`, would repaint the
     * launcher on a device while this recording stayed green and the store kept
     * showing the default artwork.
     *
     * Density and API level are the exceptions, because they vary the same
     * artwork rather than choosing different artwork: `mipmap-anydpi-v26` is how
     * an adaptive icon is *supposed* to be declared.
     *
     * Build types are covered wholesale by the source-set guard, so this only
     * has to look at what ships from `main` and `play`.
     */
    @Test
    fun `no configuration variant redefines the launcher icon`() {
        val offenders = SHIPPING_SOURCE_SETS
            .flatMap { (File("src/$it/res").listFiles() ?: emptyArray()).toList() }
            .filter { it.isDirectory && choosesByConfiguration(it.name) }
            .filter { dir ->
                dir.walkTopDown().filter { it.isFile }.any { file ->
                    file.name.startsWith(ICON_PREFIX) ||
                        (file.extension == "xml" && declaresAnIconResource(file))
                }
            }
        assertTrue(
            "These resource directories redefine the launcher icon for one " +
                "configuration: $offenders. A device in that configuration would show " +
                "artwork this recording never renders. Drop the qualified alternative, " +
                "or decide which one the listing shows and render that.",
            offenders.isEmpty(),
        )
    }

    /**
     * Artwork in the bleed reaches neither the icon nor the feature mark.
     *
     * The two used to disagree: the icon cropped to the visible square while the
     * mark measured and drew the whole layer, so a foreground painting into the
     * bleed would have been scaled into the feature graphic — showing detail no
     * launcher shows, and shrinking the mark to make room for it. Both go
     * through [visibleInk] and [bleedFor] now, and this is what holds them to it.
     */
    @Test
    fun `artwork outside the visible region is not measured`() {
        val bleedOnly = object : Drawable() {
            override fun draw(canvas: Canvas) {
                // The outer sixth of each edge is exactly the bleed: the canvas is
                // ADAPTIVE_CANVAS units and the visible square is ADAPTIVE_VISIBLE.
                val edge = bounds.height() *
                    (ADAPTIVE_CANVAS - ADAPTIVE_VISIBLE) / ADAPTIVE_CANVAS / 2f
                canvas.drawRect(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.top + edge,
                    Paint().apply { color = Color.RED },
                )
            }

            override fun setAlpha(alpha: Int) = Unit

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        assertTrue(
            "A layer painting only in the bleed was measured as visible ink, so the " +
                "feature mark would scale hidden artwork into the graphic.",
            visibleInk(bleedOnly, 1024).isEmpty,
        )
    }

    /**
     * The icon may only be built out of resources named after it.
     *
     * Both override guards find an override by *name*, which is sound only while
     * everything the icon is built from carries [ICON_PREFIX]. Repoint a layer at
     * `@drawable/logo` and a source set could override `logo` with neither guard
     * noticing — the scans would keep looking for `ic_launcher*`.
     *
     * So rather than resolving the resource graph, this closes it: every
     * reference *from* the icon — a prefix-named file, or a prefix-named entry in
     * a shared `values` file — must itself be prefix-named.
     * Since the scans cover every prefix-named resource, and no prefix-named
     * resource can reach outside that set, what the icon is built from is exactly
     * what they already look at.
     *
     * Framework references (`@android:color/…`) are exempt — no source set of
     * this app can override those. Literals are not references and are ignored.
     *
     * What this does *not* claim: that the recorded variant resolves each of
     * those names the way the shipping one does. Only recording from the variant
     * that ships answers that, which `TODO.md` carries.
     */
    @Test
    fun `the icon is built only out of resources named after it`() {
        val strays = SHIPPING_SOURCE_SETS
            .flatMap { File("src/$it/res").walkTopDown().filter { file -> file.isFile }.toList() }
            .filter { it.extension == "xml" }
            .flatMap { file -> references(file).map { file to it } }
            .filterNot { (_, name) -> name.startsWith(ICON_PREFIX) }
        assertTrue(
            "These icon resources reference resources not named after the icon: " +
                "${strays.map { (file, name) -> "$file -> @$name" }}. The override " +
                "guards find an override by name, so a layer called something else " +
                "could be replaced by a source set without either noticing. Name it " +
                "$ICON_PREFIX*, or stop building the launcher icon out of it.",
            strays.isEmpty(),
        )
    }

    /**
     * Every app resource this file references, by bare name.
     *
     * Attribute values and element text both carry them (`android:drawable`,
     * `android:fillColor`, an `<item>`'s body), so both are read. A framework
     * reference or a literal is not something a source set of this app can
     * override, so neither is returned.
     */
    private fun references(file: File): List<String> {
        // A prefix-named *file* is the icon or one of its layers, so everything in
        // it counts. A shared `values/colors.xml` is not, but the icon's own
        // entries live in it — `ic_launcher_background` is defined there — so its
        // prefix-named entries count too, and the rest of the file does not.
        val wholeFile = file.name.startsWith(ICON_PREFIX)
        return elements(file)
            .filter { wholeFile || it.getAttribute("name").startsWith(ICON_PREFIX) }
            .flatMap { element ->
                val attributes = (0 until element.attributes.length)
                    .map { element.attributes.item(it).nodeValue.orEmpty() }
                attributes + element.textContent.orEmpty()
            }
            .mapNotNull { REFERENCE.matchEntire(it.trim())?.groupValues?.get(1) }
    }

    /**
     * Whether a resource directory's qualifiers select *different* artwork.
     *
     * Everything after the resource type is a qualifier. Density and API level
     * are excluded: they pick a rendition of the same icon, which is what a
     * launcher icon is meant to have. Any other qualifier chooses artwork this
     * recording cannot see.
     */
    private fun choosesByConfiguration(directory: String): Boolean =
        directory.split("-").drop(1).any { qualifier ->
            qualifier !in DENSITIES && !resolvesEverywhere(qualifier)
        }

    /**
     * Whether an API-level qualifier can still pick different artwork.
     *
     * `-v26` cannot: every supported device is past it, so the adaptive icon it
     * qualifies is the only rendition that ever resolves — which is exactly how
     * an adaptive icon is meant to be declared. A qualifier *above* the floor
     * can: `drawable-v37` would resolve on some supported devices and not
     * others, while this recording, pinned to one SDK, sees only one of them.
     */
    private fun resolvesEverywhere(qualifier: String): Boolean {
        val api = Regex("v(\\d+)").matchEntire(qualifier)?.groupValues?.get(1) ?: return false
        return api.toInt() <= context.applicationInfo.minSdkVersion
    }

    /**
     * `linear-gradient(135deg, …)`.
     *
     * The shader axis runs along (1, 1) and its parameter has to reach 1 at the
     * far corner, which puts its end at `((w + h) / 2, (w + h) / 2)` — not at
     * `(w, h)`, which would tilt the bands with the aspect ratio.
     */
    private fun gradient(): Shader {
        val axis = (WIDTH + HEIGHT) / 2f
        return LinearGradient(
            0f, 0f, axis, axis,
            intArrayOf(
                Color.parseColor("#16223C"),
                Color.parseColor("#1B2A4A"),
                Color.parseColor("#2A3F6B"),
            ),
            floatArrayOf(0f, GRADIENT_MID, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * Draw the mark into the art box, cropped to the ink rather than to the
     * drawable's viewport, so the margins the launcher needs don't shrink it
     * here.
     *
     * The ink box is *measured*, by rendering the layer large and finding the
     * non-transparent pixels — not derived from the path geometry. Predicting it
     * means re-deriving stroke caps, joins, closed contours and curve extents,
     * which is a renderer's job and was where most of this file's predecessor's
     * bugs lived. The mark is then drawn as a vector at the target size, so
     * scaling costs no sharpness.
     */
    private fun drawMark(canvas: Canvas, left: Float) {
        val probe = 1024
        val layer = requireNotNull(adaptiveIcon().foreground) {
            "The adaptive icon carries no foreground layer."
        }
        val ink = visibleInk(layer, probe)
        assertTrue("The launcher foreground paints nothing a launcher would show.", !ink.isEmpty)

        // As fractions of the visible square, so they survive the change of scale.
        val boxLeft = ink.left.toFloat() / probe
        val boxTop = ink.top.toFloat() / probe
        val boxWidth = ink.width().toFloat() / probe
        val boxHeight = ink.height().toFloat() / probe

        val scale = min(ART_WIDTH / boxWidth, ART_HEIGHT / boxHeight)
        val markLeft = left + (ART_WIDTH - boxWidth * scale) / 2f
        val markTop = (HEIGHT - boxHeight * scale) / 2f
        // The visible square, placed so that its ink lands on that rect, with the
        // layer drawn oversized around it and the bleed clipped back off.
        val squareLeft = markLeft - boxLeft * scale
        val squareTop = markTop - boxTop * scale
        val bleed = bleedFor(scale)
        canvas.save()
        canvas.clipRect(squareLeft, squareTop, squareLeft + scale, squareTop + scale)
        layer.setBounds(
            (squareLeft - bleed).roundToInt(),
            (squareTop - bleed).roundToInt(),
            (squareLeft + scale + bleed).roundToInt(),
            (squareTop + scale + bleed).roundToInt(),
        )
        layer.draw(canvas)
        canvas.restore()
    }

    /**
     * How far an adaptive layer overhangs a visible square of this size.
     *
     * The layers are a 108-unit canvas of which only the central 72 is ever
     * shown, so a launcher draws them at 108/72 and keeps the middle. Both the
     * store icon and the feature mark go through here, which is the point: the
     * mark is measured and drawn from the same region the icon crops to, so
     * artwork in the bleed can never reach one and not the other.
     */
    private fun bleedFor(visible: Float): Float =
        visible * (ADAPTIVE_CANVAS - ADAPTIVE_VISIBLE) / ADAPTIVE_VISIBLE / 2f

    /**
     * The ink box of what a launcher shows of this layer, in a `probe`-sized
     * square — the bleed is drawn outside the bitmap and clipped away by it.
     */
    private fun visibleInk(layer: Drawable, probe: Int): Rect {
        val measured = Bitmap.createBitmap(probe, probe, Bitmap.Config.ARGB_8888)
        val bleed = bleedFor(probe.toFloat()).roundToInt()
        layer.setBounds(-bleed, -bleed, probe + bleed, probe + bleed)
        layer.draw(Canvas(measured))
        return inkBounds(measured)
    }

    /**
     * Every pixel, not a corner sample: Play rejects an icon with any
     * transparency, and a hole left by a future layer edit is far more likely
     * to be somewhere in the middle than at (0, 0).
     */
    private fun assertFullyOpaque(bitmap: Bitmap) {
        val width = bitmap.width
        val pixels = IntArray(width * bitmap.height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
        val index = pixels.indexOfFirst { it ushr 24 != 0xFF }
        assertTrue(
            if (index < 0) {
                ""
            } else {
                "The icon must be fully opaque — Play rejects transparency in the app " +
                    "icon — but (${index % width}, ${index / width}) has alpha " +
                    "${pixels[index] ushr 24}."
            },
            index < 0,
        )
    }

    /**
     * Whether this source set redefines the launcher icon, by any of the three
     * routes: the manifest's `android:icon`, a drawable or mipmap file named
     * after it, or a `values` resource carrying one of its names.
     */
    private fun redefinesTheIcon(set: File): Boolean {
        val manifest = File(set, "AndroidManifest.xml")
        if (manifest.isFile && declaresAnIcon(manifest)) return true
        return File(set, "res").walkTopDown().filter { it.isFile }.any { file ->
            file.name.startsWith(ICON_PREFIX) ||
                (file.extension == "xml" && declaresAnIconResource(file))
        }
    }

    /**
     * Refuse a separate `android:roundIcon`.
     *
     * A launcher on a round-icon device prefers `roundIcon` where one exists, so
     * declaring a distinct one gives the app a second launcher icon that this
     * never renders — and the store would show the other. Which of the two the
     * listing should show is a decision, not something to infer here, so this
     * stops rather than guessing.
     *
     * Every source set that can reach a shipped Play build, build types included:
     * a `src/release` manifest adding one would be just as invisible here, and the
     * source-set guard next door only looks for `android:icon`.
     *
     * Read from the manifests rather than `ApplicationInfo`, whose `roundIcon`
     * field is hidden framework API and not visible to a unit test.
     */
    private fun assertNoRoundIcon() {
        val declaring = (SHIPPING_SOURCE_SETS + BUILD_TYPE_SOURCE_SETS)
            .map { File("src/$it/AndroidManifest.xml") }
            .filter { it.isFile }
            .filter { manifest ->
                elements(manifest).any { declaresADistinctRoundIcon(it) }
            }
        assertTrue(
            "These manifests declare a different android:roundIcon: $declaring. Some " +
                "launchers " +
                "prefer it, so the app would have a second icon this store graphic " +
                "never renders. Decide which the listing shows and render that one, " +
                "or drop the round variant.",
            declaring.isEmpty(),
        )
    }

    /** Whether this manifest sets `android:icon`, by namespace rather than prefix. */
    /**
     * Whether an element declares a round icon that is a *different* icon.
     *
     * `android:roundIcon="@mipmap/ic_launcher"` beside the same `android:icon`
     * names one drawable twice, and nothing can diverge — the guard is about a
     * second, distinct launcher icon that this never renders, so it compares the
     * resolved names rather than merely spotting the attribute.
     */
    private fun declaresADistinctRoundIcon(element: org.w3c.dom.Element): Boolean {
        if (!element.hasAttributeNS(ANDROID_NS, "roundIcon")) return false
        val round = element.getAttributeNS(ANDROID_NS, "roundIcon")
        // Written beside the same value — including a manifest placeholder, which
        // resolves to nothing until the merge.
        if (round == element.getAttributeNS(ANDROID_NS, "icon")) return false
        // Or drawing what the merged manifest already resolved `android:icon` to,
        // which is how a source set adds one while inheriting the icon itself.
        return !drawsTheIcon(round)
    }

    /**
     * Whether this reference names artwork the merged `android:icon` also draws.
     *
     * Ids are not the test: a `mipmap` alias whose value is `@mipmap/ic_launcher`
     * is a different id and the same picture, and rejecting that would be the
     * same false positive one step along. So an id match is taken as enough, and
     * anything else is decided by rendering both and comparing — which is what
     * the guard actually cares about, and what the rest of this file already does
     * rather than reasoning about resources.
     */
    private fun drawsTheIcon(reference: String): Boolean {
        val icon = context.applicationInfo.icon
        val id = resolve(reference)
        if (id == 0 || icon == 0) return false
        if (id == icon) return true
        val round = rendered(id) ?: return false
        val declared = rendered(icon) ?: return false
        return round.sameAs(declared)
    }

    /** A drawable drawn at a fixed size, or null if the resource will not load. */
    private fun rendered(id: Int, size: Int = 128): Bitmap? {
        val drawable = try {
            context.getDrawable(id)
        } catch (e: android.content.res.Resources.NotFoundException) {
            null
        } ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** The resource id a `@type/name` reference names, or 0 for anything else. */
    private fun resolve(reference: String): Int {
        val parts = REFERENCE_WITH_TYPE.matchEntire(reference.trim()) ?: return 0
        return context.resources.getIdentifier(
            parts.groupValues[2],
            parts.groupValues[1],
            context.packageName,
        )
    }

    private fun declaresAnIcon(manifest: File): Boolean = elements(manifest).any { element ->
        element.hasAttributeNS(ANDROID_NS, "icon")
    }

    /** Whether this values file declares a resource named after the launcher icon. */
    private fun declaresAnIconResource(file: File): Boolean = elements(file).any { element ->
        element.getAttribute("name").startsWith(ICON_PREFIX)
    }

    /**
     * Every element of an XML file, parsed rather than pattern-matched.
     *
     * Substring searches were wrong twice here: `android:icon` misses a manifest
     * that binds the Android namespace to another prefix, and `name="ic_launcher`
     * misses `name = "ic_launcher"` or single quotes. Both are valid XML that
     * `aapt` reads and a `contains` call does not, and both would have left the
     * guard silently passing. A parser gets all of that right by construction, so
     * this stops being a source of edge cases.
     *
     * A file that will not parse is not treated as safe: it is reported as a
     * redefinition, because an unreadable override is not an absent one.
     */
    private fun elements(file: File): List<org.w3c.dom.Element> = try {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(file)
        val all = document.getElementsByTagName("*")
        (0 until all.length).mapNotNull { all.item(it) as? org.w3c.dom.Element }
    } catch (e: org.xml.sax.SAXException) {
        listOf(document("""<unparseable name="$ICON_PREFIX" />"""))
    } catch (e: java.io.IOException) {
        listOf(document("""<unreadable name="$ICON_PREFIX" />"""))
    }

    /** A stand-in element, so an unparseable file counts as a redefinition. */
    private fun document(xml: String): org.w3c.dom.Element =
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
            .documentElement

    /** The smallest rect covering every non-transparent pixel. */
    private fun inkBounds(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < left) Rect() else Rect(left, top, right + 1, bottom + 1)
    }

    /** Refuse a composition that would be cropped rather than centering it anyway. */
    private fun fits(measured: Float, canvas: Int, axis: String) {
        val room = canvas - 2 * SAFE_MARGIN
        assertTrue(
            "The feature graphic's content is ${measured.roundToInt()}px $axis, past the " +
                "${room.roundToInt()}px that clears the ${SAFE_MARGIN.roundToInt()}px margin " +
                "on a ${canvas}px canvas. Shorten the copy, or re-cut the layout.",
            measured <= room,
        )
    }

    /**
     * The icon this build actually installs, resolved through the merged
     * manifest rather than named here.
     *
     * `applicationInfo.icon` is what a launcher reads, so repointing
     * `android:icon` carries the store graphics with it. Naming a resource
     * would let the manifest move to a different drawable while this kept
     * rendering the old one — which still resolves, because nothing deletes it.
     */
    private fun adaptiveIcon(): AdaptiveIconDrawable {
        val declared = context.applicationInfo.icon
        assertTrue(
            "The application declares no android:icon, so there is nothing to ship.",
            declared != 0,
        )
        assertNoRoundIcon()
        // The override guards find an override by name, and the closure test keeps
        // everything the icon references inside that set — but both rest on the
        // root itself being in it. Repoint `android:icon` at `@mipmap/brand_icon`
        // and the whole graph falls outside the scans, which would then pass by
        // looking at nothing.
        assertTrue(
            "The launcher icon resolves to a resource not named $ICON_PREFIX*, so the " +
                "override guards would scan for a name nothing uses and pass by " +
                "looking at nothing. Name the icon $ICON_PREFIX*, or teach the guards " +
                "the name it has.",
            context.resources.getResourceEntryName(declared).startsWith(ICON_PREFIX),
        )
        val icon: Drawable = requireNotNull(context.getDrawable(declared)) {
            "The launcher icon is missing from the merged resources."
        }
        assertTrue(
            "The launcher icon must stay adaptive — a plain drawable has no layers to compose.",
            icon is AdaptiveIconDrawable,
        )
        return icon as AdaptiveIconDrawable
    }

    /** Recorded where the listing reads them from, not into the snapshot tree. */
    private fun capture(bitmap: Bitmap, name: String) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        // A plain `./gradlew test` asserts the invariants above but must not
        // rewrite a committed deliverable as a side effect.
        if (!recording && !verifying) return
        bitmap.captureRoboImage(filePath = "../docs/play-store/$name")
    }

    private companion object {
        const val WIDTH = 1024
        const val HEIGHT = 500
        const val GRADIENT_MID = 0.55f
        const val WORDMARK = "Snoozemo"
        const val WORDMARK_SIZE = 96f
        const val WORDMARK_TRACKING = -2f
        const val TAGLINE_SIZE = 32f
        const val TAGLINE_LEADING = 1.44f
        const val ART_WIDTH = 245f
        const val ART_HEIGHT = 292f
        const val ART_GAP = 60f
        const val COPY_GAP = 24f

        /** Where the first tagline baseline sits within its line box. */
        const val FIRST_LINE = 0.78f

        /**
         * The composition keeps clear of the edges: Play crops this graphic on
         * some surfaces and lays a play button over it when the listing has a
         * promo video. Today's layout sits far inside this, so it is a floor
         * that catches copy which no longer fits — not a tight fit to today's.
         */
        const val SAFE_MARGIN = 64f

        /** An adaptive icon's layer canvas, and the part of it any mask shows. */
        const val ADAPTIVE_CANVAS = 108f
        const val ADAPTIVE_VISIBLE = 72f

        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Every launcher-icon resource this app declares shares this prefix. */
        const val ICON_PREFIX = "ic_launcher"

        /** An app resource reference, `@type/name` or `@name`, framework ones excluded. */
        val REFERENCE = Regex("""@(?!android:)\+?(?:\w+/)?(\w+)""")

        /** The same, keeping the type, for resolving one against the merged manifest. */
        val REFERENCE_WITH_TYPE = Regex("""@(?!android:)\+?(\w+)/(\w+)""")

        /** Qualifiers that pick a rendition of one icon rather than another icon. */
        val DENSITIES = setOf(
            "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi",
            "nodpi", "tvdpi", "anydpi",
        )

        /** The source sets that reach a shipped Play build. */
        val SHIPPING_SOURCE_SETS = listOf("main", "play")

        /** Build-type source sets — the ones that can make playRelease differ from playDebug. */
        val BUILD_TYPE_SOURCE_SETS = listOf("debug", "release", "playDebug", "playRelease")

        val TAGLINE = listOf(
            "Tap Zz. The phone goes quiet.",
            "It comes back when you do.",
        )
    }
}
