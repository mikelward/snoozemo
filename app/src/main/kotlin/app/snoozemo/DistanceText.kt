package app.snoozemo

import android.content.Context
import android.content.res.Configuration
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.core.os.ConfigurationCompat
import app.snoozemo.core.DistanceUnit
import java.util.Locale

/**
 * The unit this phone measures short distances in, read from its own locale.
 *
 * Only the US measurement system takes feet. The UK's is `UK` rather than `SI`
 * — it keeps miles for road distance — but short distances there are read in
 * meters, so it falls in with everyone else rather than getting the imperial
 * form of a walk down the street.
 *
 * **One copy, two callers.** The main screen's readout (`SPEC.md` 4.6) and the
 * ongoing notification's distance both answer this, and they have to agree:
 * two surfaces disagreeing about which unit this phone uses is worse than
 * either being wrong, since the user sees both at once. The screen wraps this
 * in a `remember` keyed on the configuration; the notification is not Compose
 * and calls it directly.
 */
internal fun distanceUnitFor(configuration: Configuration): DistanceUnit {
    val locale = ULocale.forLocale(
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault(),
    )
    return if (LocaleData.getMeasurementSystem(locale) == LocaleData.MeasurementSystem.US) {
        DistanceUnit.FOOT
    } else {
        DistanceUnit.METER
    }
}

/**
 * A whole number of [unit], with the unit's own translatable abbreviation.
 *
 * Formatted together and interpolated as one placeholder wherever it is used,
 * so a sentence carrying a distance needs one string rather than a metric and
 * an imperial copy — which is also the shape a translator wants, since where
 * the unit sits in a sentence differs by language.
 */
internal fun distanceText(context: Context, unit: DistanceUnit, value: Int): String =
    context.getString(
        when (unit) {
            DistanceUnit.METER -> R.string.distance_meters
            DistanceUnit.FOOT -> R.string.distance_feet
        },
        value,
    )

/**
 * A snapped distance, or the bound below which this fix cannot resolve one.
 *
 * `null` is what [DistanceUnit.snap] returns for a value that does not reach a
 * whole [step], and it is rendered `< 5 m` rather than rounded to something.
 * The alternative is a number the reading has not earned: `0 m` contradicts a
 * snooze that has not ended, and `1 m` under a ±25 m fix is a rounding artifact
 * wearing a unit.
 *
 * Wrapping the formatted distance rather than taking the number keeps one
 * string for the bound instead of a metric and an imperial copy, which is the
 * same shape the pair it wraps already has.
 */
internal fun distanceText(
    context: Context,
    unit: DistanceUnit,
    value: Int?,
    step: Int,
): String = value?.let { distanceText(context, unit, it) }
    ?: context.getString(R.string.distance_under, distanceText(context, unit, step))
