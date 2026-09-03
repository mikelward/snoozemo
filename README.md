# Snoozemo

Snoozemo puts your Android phone into Do Not Disturb **until you leave where you are right
now**. One tap on a Quick Settings tile arms it; walking away disarms it. No timers to
guess at, no remembering to turn DND back off.

> Silence your phone until you leave or your meeting ends.

- **What it does and why**: [SPEC.md](SPEC.md)
- **Plan**: [TODO.md](TODO.md)
- **Money**: [MONETIZATION.md](MONETIZATION.md)
- **Engineering conventions**: [AGENTS.md](AGENTS.md)

**Status: scaffold.** The build, the module layout, and CI are in place; the product isn't
built yet — `TODO.md` Phase 1 starts it. The app currently launches to a placeholder.

## Modules

`:core` is a plain Kotlin JVM module — no Android SDK on its classpath, so the state
machine stays testable without a device (`./gradlew :core:test`). `:dnd`, `:presence`, and
`:tile` are Android libraries; `:app` holds the UI and picks a `play` or `direct` flavor
(see `SPEC.md` §3.4 and §11).

## Building

```sh
./gradlew assembleDebug   # build debug APK
./gradlew test            # unit tests
./gradlew lint            # lint
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).
