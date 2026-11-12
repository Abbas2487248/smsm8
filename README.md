# smsm8

Android prototype for a transparent 8 Ball Pool aiming-guide overlay.

## Current prototype
- Kotlin Android app with a basic home screen.
- Requests permission to display a transparent overlay over other apps.
- Starts and stops a non-touchable guide overlay.
- Requests the user's explicit permission for screen capture using Android MediaProjection.
- Runs screen capture in a foreground service and logs the dimensions of incoming frames.

## Not implemented yet
- Detecting the table, cue ball, object balls, cushions, or pockets.
- Reading the live aim direction and updating trajectories.
- Collision and cushion-reflection calculations.

The guide lines are currently illustrative only. Screen frames are received but are not yet analyzed or stored. The app does not automate shots.

## Build
Open this repository in Android Studio and let Gradle sync. Build the `app` module with an Android SDK installed (compile SDK 35).

## Screen capture test
1. Install and open the app.
2. Tap **السماح بالتقاط الشاشة**.
3. Accept Android's screen-sharing prompt.
4. Check Logcat for the tag `smsm8-capture` and messages containing `Screen frame received`.
5. Tap **إيقاف التقاط الشاشة** to stop the capture service.
