# Architecture

How Extinction Event turns a few system signals into a scene, and how the scene is drawn.

## Overview

```
WallpaperService engine ──signals──▶ SceneDriver ──▶ SceneState (pure state machine)
        │                                                     │ snapshot: state, p, meteorT, impactT
        └── surface, visibility ──▶ RenderLoop (GL thread) ───┴──▶ GlRenderer ──▶ shaders (GLSL ES 3.00)
                                                                        ▲
                                                         tuning.json ───┘
```

- `scene/` is pure Kotlin with no Android or GL dependencies, tested on the JVM.
- `render/` owns EGL and the GLES 3 pipeline, plus the pure scheduling and sizing decisions in
  `RenderPolicy` and the tuning-derived constants in `SceneConstants`.
- `wallpaper/` is the Android glue: the `WallpaperService`, its render loop and the launcher entry.

Every look and timing parameter lives in `app/src/main/assets/config/tuning.json`. Shaders live
in `app/src/main/assets/shaders/` and are read at run time.

## Signals

A live wallpaper gets no direct lock-screen events, so the engine combines what it does get:

| Signal | Meaning |
|---|---|
| `android.wallpaper.wakingup` command, `ACTION_SCREEN_ON` | Screen on; `locked = KeyguardManager.isKeyguardLocked()` |
| `ACTION_SCREEN_OFF` | Screen off: the scene resets |
| `isKeyguardLocked()` turning false | Unlock. Polled on rendered frames, only during the approach. |
| `samsung.android.wallpaper.pause` / `resume` commands | One UI: a lock-screen drag starts / ends |
| Visibility | Pauses and resumes rendering; never replays the impact |

The lock screen and the home screen share one engine. Polling the keyguard rather than waiting for
`ACTION_USER_PRESENT` matters: that broadcast does not reach a wallpaper on every launcher, and a
face match that leaves the lock screen up must not count as an unlock.

The wallpaper picker's preview engine plays a canonical sequence instead (screen on locked at 0 s,
unlock at `scrubUnlockAt`) on a loop.

## Scene state machine

States `OFF`, `APPROACH`, `DIVE`, `IMPACT`, `AFTERMATH`; events `SCREEN_ON {locked}`, `UNLOCK`,
`SCREEN_OFF`, `DRAG_START`, `DRAG_END` and `tick(dt)`.

| From | Event | To | Notes |
|---|---|---|---|
| `OFF` | `SCREEN_ON {locked: true}` | `APPROACH` | `approachT = 0` |
| `OFF` | `SCREEN_ON {locked: false}` | `DIVE` | From `p = 0` |
| `OFF` | `UNLOCK` | `DIVE` | The unlock can be seen before the screen-on |
| `APPROACH` | `UNLOCK` | `DIVE` | From the current `p` |
| `DIVE` | the dive ends | `IMPACT` | `impactT = 0` |
| `IMPACT` | `impactT ≥ impactDuration` | `AFTERMATH` | `impactT` keeps counting |
| any | `SCREEN_OFF` | `OFF` | Full reset |

Anything else, including duplicates, is ignored. Derived values:

- **Approach:** `p = pHold · (1 − exp(−approachT / tau))`. The meteor never strikes on its own; it
  holds just short. While the lock screen is dragged, `approachT` runs `dragBoost` times faster.
- **Dive:** lasts `diveBase + diveExtra · (pHold − p0) / pHold`, with `p = p0 + (1 − p0) · u²` for
  `u` from 0 to 1: an accelerating fall that is short but never instant.
- **Impact and aftermath:** `impactT` runs from 0 through `impactDuration` and on.
- `meteorT` accumulates through the approach and the dive and then stops: the buried rock no longer
  turns. Scene `time` restarts at every screen-on, which keeps noise inputs small.

`src/test/resources/vectors.json` pins this behaviour with timed event lists and expected samples.

## Shader contract

The shaders never see the state; they see continuous values.

| Uniform | Meaning |
|---|---|
| `uResolution` | Render target size, pixels |
| `uTime` | Seconds since the last screen-on |
| `uMeteorP` | Path progress, 0..1 (1 = touching the surface) |
| `uMeteorT` | Seconds since the meteor appeared; −1 when off |
| `uImpactT` | Seconds since impact; −1 before |
| `uOffset` | Home-screen page offset, 0..1 |
| `uCameraShift` | Page offset, slow sway and impact shake as a shift of screen space, per frame |
| `uFocal`, `uEarthCenter`, `uImpactWorld`, `uImpactNormal`, `uMeteorVel` | Worked out from tuning once, not per pixel |

Each tuning key `fooBar` becomes the uniform `uFooBar`: a number is a `float`, an array of 2–4
numbers a `vec2`–`vec4`, and a `#rrggbb` string a linear-space `vec3`. Uniforms a shader does not
declare are skipped, so a new tunable needs no Kotlin change. Shaders share code through a
`//#include file` line (not recursive); `common.glsl` holds the camera and noise, `bodies.glsl` the
rock, the fragments and their lanes.

Screen space `q` is centred and measured in screen heights; camera space has the camera at the
origin looking down −z and an Earth of radius 1.

## Rendering

Each frame, into an HDR (`RGBA16F`) scene target of render size:

1. **Background** (cached): stars, the Earth from NASA imagery, clouds, atmosphere. Re-rendered at
   `bgRefreshHz`, on page-offset changes, and every frame for `3 · shockTime` after the impact
   while the camera shakes and the shock front spreads; between refreshes the final pass moves it
   with the camera.
2. **Impact layer** (`fx.frag`, full screen, writes every pixel): shock front, condensation and dust,
   ejecta, fires, crater, smoke and fireball.
3. **Fragments behind the meteor** (`chunks.vert`/`.frag`): instanced quads, each bounding one
   ray-marched rock and its trail, or a landing flash. Lane state is computed per vertex.
4. **Meteor** (`meteor.vert`/`.frag`): the ray-marched rock, plasma sheath and trail. After the
   impact the rock no longer moves, so its geometry is cached with the background (`rockgeo.frag`)
   and only lit per frame.
5. **Fragments in front of the meteor** (after the impact, by depth).
6. **Particles:** sparks, ejecta and embers, analytic functions of time, instanced.
7. **Flash** (the first seconds after the impact), added over everything.
8. **Bloom:** four-level dual-Kawase down- and upsampling.
9. **Final:** heat distortion, composite over the background, ACES tone mapping, vignette, dither.

Nothing is simulated or stored between frames apart from the caches, so any moment renders exactly
(`DEBUG_SCRUB`).

## Cost

- Render size: as if the surface were at most 2340 px tall, times `renderScale`. The wallpaper's
  surface is fixed at that size (behind a hidden switch; full size if unavailable) and the display
  hardware scales it up.
- Frame caps: `fpsActive` while approaching and striking, `fpsIdle` in the aftermath, `fpsSettled`
  once `settleAfter` seconds have passed since the impact. Nothing renders while the wallpaper is
  hidden; the render thread wakes only for frames it draws, and the surface asks the display for
  that frame rate.
- GL setup failures leave the wallpaper blank instead of crash-looping the process; a lost EGL
  context is rebuilt.
