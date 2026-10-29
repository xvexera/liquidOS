# Changelog

What changed in liquidOS, newest first. For the project it grew from, see the
[cleanyourtv releases](https://github.com/Gathin23/cleanyourtv/releases).

## 2026-10-29

### New: Continue watching
- **The videos you left halfway, on Home**: Liquid Glass cards with the picture, title, a progress bar and the
  time left. Finished videos, clips under 4 minutes, live streams without a length and music apps stay out.
- **Back to the exact minute**: OK reopens the video in the app's own player while it is still open. On YouTube
  it opens at the minute you stopped even after the app was closed: the video is found once by a YouTube search
  for its title and channel, and YouTube's own picture goes on the card.
- **An X under each card** removes it; Down from a card reaches it, so Left and Right go straight from card to card.
- **App picker** in Launcher settings: choose which apps it follows. New apps start switched on.
- **On or off** in Settings > Widgets; switching it off also deletes the list. The list lives only on the TV.

### Widgets
- **One sliding row** for the market card, now playing and Continue watching. It glides sideways to the card
  that takes focus when they do not fit.
- **Edit order** in Settings > Widgets: OK picks a widget up, Up and Down move it, OK puts it down.
- **Market card keeps its size** while it rotates between assets: a fixed price column that shrinks a long
  number to fit, and the chart keeps its place before an asset's history arrives.

### Look and speed
- **Glass follows the slide**: while the widgets row slides, each card keeps its content and most of its glass
  in a cached layer and only the wallpaper right behind it is drawn again each frame. Measured on an onn. 4K
  box: 30 ms a frame, about the same as the row with no glass at all (53 ms before).
- **Lighter focus**: the glass of widget cards switches at once and Home's focus light fades in over it, the
  same light as the top buttons, and rides along while the row slides.
- **App rows fade out under the widgets** when you scroll down, instead of being cut short of them. Only that
  strip goes through a small offscreen layer, only while the grid is scrolled.
- **Faster focus glow** on the widget cards and the top buttons.
- **A clear tick** (blue, larger) on the current choice in menus, such as the number of apps in the dock.

### Settings
- **Advanced effects need Liquid Glass**: with the glass off they turn off and grey out; Animations stays
  available on its own.

## 2026-10-27

- **Calendar on older TVs**: a date call that exists only from Android 14 is gone, so the calendar widget no
  longer stops on TVs with Android 12 or older.
- **Privacy** section in the README: what liquidOS sends where (nothing about you) and why it asks for location.
- **Build checks**: lint errors fixed, the APK size budget raised for liquidOS, and the CI actions updated.

## 2026-10-25: first public version

- Liquid Glass home screen: glass pills, dock and widgets over the wallpaper, Apple TV style focus, glass clock,
  two icon packs and optional real-time glass (Advanced effects).
- Widgets: market ticker, now playing and calendar.
- Night mode: artificial dimming, warm light and real TV brightness.
- Wallpapers in folders (rotating, colours, NASA, Retro) and upload from a phone with a QR code.
- liquidOS Installer for Windows, in English and Spanish: finds the TV, backs it up, debloats it, installs
  liquidOS, makes it Home safely and can undo everything.
- Credits to Gathin's cleanyourtv, MIT license, third-party notices.
