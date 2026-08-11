# Snoozemo

Snoozemo puts your Android phone into Do Not Disturb **until you leave where you are right
now**. One tap on a Quick Settings tile arms it; walking away disarms it. No timers to
guess at, no remembering to turn DND back off.

> Tap Zz. The phone goes quiet. It comes back when you do.

- **What it does and why**: [SPEC.md](SPEC.md)
- **Plan**: [TODO.md](TODO.md)
- **Engineering conventions**: [AGENTS.md](AGENTS.md)

**Status: design only.** The spec and the plan are written; the app is not built yet — see
`TODO.md` Phase 0. Nothing below works until that lands.

## Building

```sh
./gradlew assembleDebug   # build debug APK
./gradlew test            # unit tests
./gradlew lint            # lint
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).
