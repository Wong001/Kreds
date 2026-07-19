# tor-android vendoring notes (Task 7, 2026-07-19)

- artifact: info.guardianproject:tor-android:0.4.9.11
- arm64-v8a libtor.so: present, 7,576,992 bytes (~7.2 MiB) at jni/arm64-v8a/libtor.so inside the AAR (armeabi-v7a, x86, x86_64 libtor.so also present in the same AAR)
- tor_api symbols (llvm-nm -D --defined-only, arm64-v8a libtor.so):
  ```
  000000000030c520 T tor_main_configuration_free
  000000000030c214 T tor_main_configuration_new
  000000000030c288 T tor_main_configuration_set_command_line
  000000000030e28c T tor_run_main
  ```
  All four present and exported (T = defined in .text). Bonus symbols also exported that Task 8 may find useful: `tor_main_configuration_setup_control_socket`, `tor_main`, `tor_api_get_provider_version`, `tor_init`, `tor_cleanup`.
- minSdk: 24 (from the AAR's own AndroidManifest.xml: `<uses-sdk android:minSdkVersion="24" />`, package `org.torproject.jni`, single extra component `<service android:name="org.torproject.jni.TorService" />`). Confirmed <= 30. The app's merged debug manifest (`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`) keeps effective minSdk 24 with no conflict, and shows the TorService entry merged in from the AAR.
- W^X packaging: confirmed. libtor.so (and the other three ABI variants) live only inside the AAR's `jni/<abi>/` directory, which Android packages into the APK's native-lib area; it is loaded via JNI (`System.loadLibrary`/dlopen), never `exec()`'d from writable storage. The app's merged manifest additionally sets `android:extractNativeLibs="false"`, meaning the library is mmap'd directly out of the (read-only) APK at runtime rather than being extracted to writable app storage at install time -- a stronger-than-required W^X posture, not a deviation.
- DECISION: GO: primary JNI path

## Build blocker found during verification (separate from the JNI-surface question above)

`.\gradlew assembleDebug` currently **FAILS**, not because of anything related to the JNI surface, but because of an AAR metadata gate:

```
Task :app:checkDebugAarMetadata FAILED
> Dependency 'info.guardianproject:tor-android:0.4.9.11' requires libraries and
  applications that depend on it to compile against version 37 or later of the
  Android APIs.
  :app is currently compiled against android-36.
```

Root cause confirmed directly in the AAR's `META-INF/com/android/build/gradle/aar-metadata.properties`:
```
minCompileSdk=37
```
The project's `app/build.gradle` sets `compileSdk rootProject.ext.compileSdkVersion`, which currently resolves to 36 (Expo SDK 57 default: buildTools 36.0.0, compileSdk 36, targetSdk 36 -- see the `:app` configure-project log: "compileSdk: 36").

This is orthogonal to the tor_api symbol question (all four symbols ARE present and exported -- see above), but it does mean **no debug APK can currently be assembled** with this dependency in place. Task 8 cannot be built/run end-to-end until either:
- `compileSdk` (and likely the Android Gradle Plugin version, since AGP 8.12.0's max recommended compileSdk is 36) is bumped to >= 37 project-wide, or
- a different tor-android release with a lower `minCompileSdk` is substituted (out of scope for this task -- Step 1/2 already locked in 0.4.9.11 and resolving it was explicitly not to be redone here).

This compileSdk bump touches the whole app (not just the spike's dependency line) and was outside this task's authorized file scope (`build.gradle` dependency line + `NOTES.md` only), so it was not attempted here. Flagging for the controller to resolve before Task 8 work can be built and exercised.
