# Opendrone

Small Android app for viewing a simple UDP MJPEG stream from a cheap Temu drone.

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

Install on a connected phone (check `adb devices` first):

```sh
./gradlew :app:installDebug
```

This is an alternative to **com.tzh.wifi.wificam.activity**, which I find bloated and difficult to use.
