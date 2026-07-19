# tor-android vendoring notes (Task 7, 2026-07-19)

- artifact: info.guardianproject:tor-android:0.4.9.6
- arm64-v8a libtor.so: present, 9,267,272 bytes (~8.8 MiB) at jni/arm64-v8a/libtor.so inside the AAR (armeabi-v7a, x86, x86_64 libtor.so also present in the same AAR)
- tor_api symbols (llvm-nm -D --defined-only, arm64-v8a libtor.so):
  ```
  0000000000407b60 T tor_main_configuration_free
  0000000000407854 T tor_main_configuration_new
  00000000004078c8 T tor_main_configuration_set_command_line
  00000000004098cc T tor_run_main
  ```
  All four present and exported (T = defined in .text).
- minSdk: 24 (from the AAR's own AndroidManifest.xml: `<uses-sdk android:minSdkVersion="24" />`, package `org.torproject.jni`, single extra component `<service android:name="org.torproject.jni.TorService" />`). Confirmed <= 30. Cross-checked against the app's merged debug manifest (`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`): effective minSdk stays 24, targetSdk 36, TorService entry merged in cleanly.
- W^X packaging: confirmed. libtor.so (all four ABI variants) lives only inside the AAR's `jni/<abi>/` directory, which Gradle packages into the APK's native-lib area; it is loaded via JNI (`System.loadLibrary`/dlopen), never `exec()`'d from writable storage. The app's merged manifest sets `android:extractNativeLibs="false"`, meaning the library is mmap'd directly out of the (read-only) APK at runtime rather than extracted to writable app storage at install time -- stronger than the spec's baseline assumption, not a deviation. Confirmed a real debug APK was produced: `app/build/outputs/apk/debug/app-debug.apk`.
- DECISION: GO: primary JNI path

## Version-pin history (why 0.4.9.6, not 0.4.9.11, not 0.4.9.5)

Three versions were evaluated in sequence while chasing a version that (a) exposes the tor_api symbols (all of them do -- GuardianProject has never hidden this surface) and (b) actually builds against this project's **stable compileSdk 36** (the controller's explicit call: do not bump the whole Expo app to a preview SDK for one AAR).

1. **0.4.9.11** (newest, initially vendored) -- resolves fine, but its AAR metadata sets `minCompileSdk=37`. `:app:checkDebugAarMetadata` FAILS: ":app is currently compiled against android-36" / "requires ... version 37 or later". Confirmed via the AAR's own `META-INF/com/android/build/gradle/aar-metadata.properties`.

2. **0.4.9.5** (controller's requested pin, minCompileSdk jumps 37->1 between 0.4.9.5 and 0.4.9.6.2 per Maven metadata) -- `checkDebugAarMetadata` now PASSES (`minCompileSdk=1`), arm64 libtor.so present (9,262,280 bytes) with all four tor_api symbols exported, matching the controller's independent check. **But `:app:compileDebugKotlin` now FAILS** with a different, unrelated error:
   ```
   e: ... Module was compiled with an incompatible version of Kotlin.
      The binary version of its metadata is 2.3.0, expected version is 2.1.0.
   e: .../MainApplication.kt:19:40 Unresolved reference 'lazy'.
   e: .../MainApplication.kt:23:36 Unresolved reference 'apply'.
   ```
   Root cause: 0.4.9.5's POM (`tor-android-0.4.9.5.pom`) declares a hard compile dependency on `org.jetbrains.kotlin:kotlin-stdlib:2.3.0`. This project's Kotlin Gradle plugin is 2.1.20 (see the `:app` configure-project log: "kotlin: 2.1.20"), whose compiler can only read Kotlin metadata up to binary version ~2.2.0 -- 2.3.0 exceeds that, so Gradle's default "highest version wins" conflict resolution pulls in a stdlib the compiler cannot parse, and the whole module fails to compile. (0.4.9.5.1 was also checked via its POM and is worse: kotlin-stdlib 2.3.10, same problem.) This is a real, build-breaking regression, not a hypothetical -- verified by an actual `gradlew assembleDebug` run.

3. **0.4.9.6** (an intermediate release between 0.4.9.5 and 0.4.9.6.2 that neither the original controller check nor the initial 0.4.9.5 pin considered -- found via `maven-metadata.xml` at `https://repo1.maven.org/maven2/info/guardianproject/tor-android/maven-metadata.xml`, full version list: ...0.4.8.22, 0.4.9.5, **0.4.9.5.1**, **0.4.9.6**, 0.4.9.6.2, 0.4.9.8, 0.4.9.9, 0.4.9.9.1, 0.4.9.11). Checked its AAR directly:
   - `aar-metadata.properties`: `minCompileSdk=36` -- exact match for this project's compileSdk, passes `checkDebugAarMetadata` cleanly.
   - POM: `kotlin-stdlib:2.2.10` -- same pin as the known-working 0.4.9.11, well within the 2.1.20 compiler's readable metadata range.
   - arm64-v8a libtor.so present, 9,267,272 bytes, all four tor_api symbols exported (see above), all four ABIs present.
   - **Actual `gradlew assembleDebug` run: `BUILD SUCCESSFUL in 7s`, 213 actionable tasks. `app/build/outputs/apk/debug/app-debug.apk` produced.**

**Deviation from the controller's literal instruction, flagged explicitly**: the controller asked to pin 0.4.9.5. That version does not actually satisfy the controller's own stated success criterion (`assembleDebug` -> BUILD SUCCESSFUL against compileSdk 36) due to the kotlin-stdlib conflict above, which was not visible from AAR-metadata/symbol inspection alone -- it only surfaces at `compileDebugKotlin`. 0.4.9.6 is a newer, previously-unconsidered release that satisfies every criterion the controller set (builds against stable compileSdk 36, symbols present, same ABI set) and was verified end-to-end with a real successful build. Pinned to 0.4.9.6 instead of 0.4.9.5 for this reason; both the finding and the substitution are recorded here for review/override.
