# Input methods (VM settings › Controls)

Two optional input methods, both off by default, set per VM (and as defaults in App settings).

## Mouse and keyboard
* The guest gets an emulated **touchpad** (`AEmulator Touchpad`) and **keyboard** (`AEmulator Keyboard`); its
  touch screen (`/dev/input/event0`) is removed. The guest screen is the touchpad: tap = click, two fingers = scroll
  (the guest's own gesture code, Android 3.2+; older builds show no pointer).
* A **keyboard** button appears in the navigation bar. It opens a floating, half-transparent keyboard (opacity slider,
  drag by the title bar). Shift/Ctrl/Alt latch for the next key. While this mode is on, *every* key (navigation bar,
  volume, power, a connected hardware keyboard) is sent through the keyboard device.
* Applies at the next full VM boot. Works on every engine and firmware architecture: the qemu engines (ARM and x86-32
  guests, nodes from `libaemushim`) and the native arm64 engine (nodes from the runner `libaemu64_<api>`, sockets
  `AEMU64_PAD` / `AEMU64_KBD`). Not available in recovery or framework-less boots (the toggle is ignored there).
* Implementation: `native/guestshim/vinput.h` (same technique as `trackball.h`: an empty node in `/dev/input`, `open()`
  returns a socket to the app, `EVIOC*` answered in the shim), `core/InputService.kt` (servers `dev/aemu_touchpad`,
  `dev/aemu_keyboard`), `core/VirtualInput.kt` (protocol, layout, latch). **`libaemushim.so` must be rebuilt**
  (`native/guestshim/build.sh`) and copied to `app/src/main/assets/engines/common/`.

## Keypad
* A **keypad** button in the navigation bar opens a floating window: soft keys (Menu/Back), arrows + OK, Call/End,
  0–9 * #, like a feature phone. The keys go through the ordinary key device, so **touch stays on** and the method can
  be combined with mouse and keyboard. Works on every engine.
* Digits, Call, End, * and # are added to the emulator's key layout (`dhd-touch.kl`, `TreeFixer.keyLayout`).
