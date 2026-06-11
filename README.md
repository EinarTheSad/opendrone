# Opendrone

Small Android app for viewing a simple UDP MJPEG stream from a Wi-Fi drone.

This is an early personal project, built in Kotlin without Android Studio-specific
project files.

## Build

Install the Android SDK command line tools, then create `local.properties` with
your SDK path:

```properties
sdk.dir=/path/to/android-sdk
```

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Install on a connected phone:

```sh
./gradlew :app:installDebug
```

## Notes

- The app currently targets Android SDK 34.
- Drone connection details are still hardcoded in `DroneVideoReceiver.kt`.
- Recording is saved as MJPEG AVI through Android MediaStore.
