# R8 keep rules for the minified builds — the CI `debug` and `release` builds
# (isMinifyEnabled = isCiBuild in app/build.gradle.kts). Keep this list tight:
# each rule names why it exists.

# Full R8 — shrinking, optimization and obfuscation all on. This is a Play
# requirement, not a size preference: from February 2027 an app must show a
# minimum of 25% coverage across optimization, shrinking and obfuscation, and
# apps below the threshold lose visibility and publishing capability. A
# shrink-only run (`-dontoptimize -dontobfuscate`) leaves two of those three
# dimensions at zero, so it is not an option here. SPEC.md §3.7 has the
# trade-offs this buys and costs.

# --- Enum constants that round-trip through disk ------------------------------
# Three stores persist an enum by `name()` and read it back with `valueOf`:
# ActiveSnoozeStore (TrackingMode — the live snooze record, which must survive
# process death and reboot), PendingFailureStore (ZenFailure) and
# CapabilityLossStore (CapabilityLossCause). Obfuscation renaming a constant, or
# R8 unboxing the enum to an int, would make a record written by one build
# unreadable by the next: the snooze degrades to duration-only at best, and at
# worst a rule stays on with nothing left that knows how to turn it off
# (SPEC.md D7, and the "never leave the phone silently quiet" principle).
# `valueOf` is what the shipped default rules keep; the field names it resolves
# against are what these keep.
-keepclassmembers enum app.snoozemo.core.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
