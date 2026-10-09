# RikkaHub AvatarSize (LSPosed module)

> **Repository:** https://github.com/tsc1994/RikkaHubAvatarSize
> **Download:** see the [Releases](https://github.com/tsc1994/RikkaHubAvatarSize/releases) page for a prebuilt APK.

An Xposed/LSPosed module that **enlarges the chat avatars — both the user avatar and the assistant avatar — inside [RikkaHub](https://github.com/rikkahub/rikkahub)**.

RikkaHub hard-codes both chat avatars to **28.dp** and exposes **no setting** for it:

```kotlin
// app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt
UIAvatar( modifier = Modifier.size(28.dp), ... )   // user
UIAvatar( modifier = Modifier.size(28.dp), ... )   // assistant
```

This module hooks the Compose sizing helper
`androidx.compose.foundation.layout.SizeKt.size()` at runtime and rewrites the
value **only when the call originates from the chat-avatar composables**
(`ChatMessageAvatarKt`), via a stack-trace check. Icons, buttons, spacing and
every other UI element are left untouched.

```
Default (28dp)                       With this module (56dp)
[avatar] message text                [  avatar  ] message text
```

> This hooks the app at runtime, so it works **without patching RikkaHub's APK**:
> no data loss, survives app updates, and is trivially reversible (just disable
> or uninstall the module).

---

## Requirements

- Android 8.0+ (API 26+)
- [LSPosed](https://github.com/LSPosed/LSPosed) (tested on LSPosed 2.1.1 / API 102)
- RikkaHub installed

## Install

1. Build the APK (see below) or grab it from **Releases**.
2. Install it: `adb install app-release.apk`
3. Open **LSPosed → Modules**, enable **AvatarSize (RikkaHub)**, and check the
   **RikkaHub** scope.
4. Force-stop / restart RikkaHub. Done.

If avatars are still 28dp, cold-start RikkaHub once (kill it from recents and
reopen) so the module is injected into a fresh process.

## Configuration

Two constants at the top of
`app/src/main/java/com/avatarsize/lsposed/AvatarSizeHook.java`:

```java
private static final float OLD_SIZE = 28.0f;  // value RikkaHub hard-codes
private static final float NEW_SIZE = 56.0f;  // desired size (change me)
```

Change `NEW_SIZE`, rebuild, reinstall. That's it.

By default the module targets the **debug** package `excp.rikkahub.debug`.
If you run the release build (`me.rerere.rikkahub`), edit `TARGET_PKG` in the
same file and the scope array in
`app/src/main/res/values/arrays.xml`.

## Build

### Option A — Gradle (standard)

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk  (sign it, then install)
```

### Option B — plain `javac` + `d8` + `aapt2` (no Gradle)

A minimal script that only needs a JDK, the Android SDK build-tools and
platform jar is in [`scripts/build-manual.sh`](scripts/build-manual.sh).
This is the exact toolchain used to produce the first working build.

## How it works (short version)

1. `handleLoadPackage` fires for `excp.rikkahub.debug`.
2. Every `SizeKt.size(modifier, float)` overload is hooked.
3. On each call, if the value is `28.0f` **and** the call stack contains
   `ChatMessageAvatarKt`, the argument is replaced with `NEW_SIZE`.

Because the scope is narrowed by both the *value* and the *call site*, only the
two chat avatars change.

## Reversibility

- Disable the module in LSPosed → avatars return to 28dp on next app restart.
- Uninstall `com.avatarsize.lsposed` → everything back to stock.

No files inside RikkaHub are modified.

## Notes / background

The avatar size in RikkaHub is a hard-coded constant with no preference, and
the upstream project does not (as of this writing) offer a size setting. This
module is a stop-gap until/unless upstream adds one. See RikkaHub issues
[#1976](https://github.com/rikkahub/rikkahub/issues/1976) (image-picker / crop
bug) and [#2127](https://github.com/rikkahub/rikkahub/issues/2127) (avatar
deletion on file cleanup) for related avatar pain points.

## License

Apache-2.0 — see [LICENSE](LICENSE).
