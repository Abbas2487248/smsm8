# smsm8

Android prototype for a transparent 8 Ball Pool aiming-guide overlay.

## Current prototype
- Kotlin Android app with a basic home screen.
- Requests the Android "display over other apps" permission.
- Starts and stops a transparent, non-touchable overlay.
- Draws temporary demonstration guide lines.

## Not implemented yet
- Screen capture through Android MediaProjection.
- Detecting the table, cue ball, object balls, cushions, or pockets.
- Reading the live aim direction and updating trajectories.
- Collision and cushion-reflection calculations.

The current lines are only a visual overlay test. They are not connected to the game and do not automate shots.

## Build
Open this repository in Android Studio and let Gradle sync. Build the `app` module with an Android SDK installed (compile SDK 35).
