# PortalEX Camera Simulation — standard frame channel

This directory defines the contract between PortalEX (app / frame producer)
and a device-side camera simulation backend.

## Layers

- Control/playback: `VcamFramePlayer` (Kotlin) implements `load / pause / resume /
  limit / loop / stay` on the app side.
- Data plane: the shared frame file already used by `CameraFrameSink` and the
  camera HAL shim. The backend reads the newest published NV21 frame only when
  the camera stack actually requests a frame.
- Device backend: an implementation of the shared-frame reader
  (`camera-simulation/oneplus/PKG110/provider/src/frame_reader.cpp` is the OnePlus one).

## Playback semantics

`load(frames, clear=false)`
  - clear=true: replace the sequence, reset index to 0, clear paused/ended.
  - clear=false: append; empty list is a no-op.
  - empty list + clear=true: clear the sequence.

`pause(index)`
  - 0: pause at the current frame.
  - >0: 1-based frame index; invalid index returns an error.
  - <0: error.

`resume(index)`
  - 0: resume from the current frame.
  - >0: jump to the 1-based frame and play.
  - <0 or >count: error.

`limit(page)`
  - page = maximum output fps, 0 = unlimited, negative = error.
  - When the camera requests faster than this, the same frame is returned.
  - The declared `maxFps` capability becomes `min(page, hardware max)`.

`loop(state)`
  - true: wrap to frame 0 after the last frame.
  - false: enter ENDED after the last frame.

`stay(state)`
  - true: keep the last frame when there is no new frame.
  - false: output black at EMPTY / ENDED without loop.

## Idle behaviour

No camera capture request => the backend does not call `nextFrame`, does not
convert or copy frames. `load / pause / resume / limit / loop / stay` are still
accepted and only update state.

## Shared frame file

Path: `/data/local/tmp/portalex/cammock.frames`

The header is the one defined by `camera-hal/src/camshim.c` and
`CameraFrameSink.kt`. This subproject adds one optional header word at offset
108: `consumer_req_seq` (int32). The device backend increments it on every
capture request; the app-side player polls it and only publishes a frame when
it changed. Backends that do not implement this word can leave it at 0 and the
player falls back to a capped idle tick.
