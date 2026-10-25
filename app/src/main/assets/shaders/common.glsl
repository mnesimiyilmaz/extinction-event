// Shared by every pass; included after the #version line. Camera space: camera at the origin
// looking down -z, x right, y up. Screen position q: centred, in units of screen height.

uniform vec2 uResolution;   // render target size, pixels
uniform float uTime;        // seconds since screen-on
uniform float uParallaxEarth;
uniform float uImpactT;     // seconds since impact; -1 before
uniform vec3 uMeteorStart;  // meteor start position, camera space
// Worked out by the renderer from tuning (SceneConstants.kt) rather than per pixel:
uniform float uFocal;       // 0.5 / tan(camFovY / 2)
uniform vec3 uEarthCenter;  // camera space; the Earth's radius is 1
uniform vec3 uImpactWorld;  // the point on the Earth seen at impactQ
uniform vec3 uImpactNormal;
uniform vec3 uMeteorVel;    // meteor direction of travel, start → impact
uniform vec2 uCameraShift;  // per frame (cameraShift): page offset + sway + impact shake, for amount 1

const float PI = 3.14159265;

// x² without pow(): pow(x, y) is undefined for x < 0 (GLSL ES 3.00 §8.2).
float sq(float x) { return x * x; }

// Hash without sine (Dave Hoskins).
float hash11(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}
float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float hash13(vec3 p3) {
  p3 = fract(p3 * 0.1031);
  p3 += dot(p3, p3.zyx + 31.32);
  return fract((p3.x + p3.y) * p3.z);
}

float noise2(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
             mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float noise3(vec3 p) {
  vec3 i = floor(p);
  vec3 f = fract(p);
  vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  return mix(
    mix(mix(hash13(i), hash13(i + vec3(1.0, 0.0, 0.0)), u.x),
        mix(hash13(i + vec3(0.0, 1.0, 0.0)), hash13(i + vec3(1.0, 1.0, 0.0)), u.x), u.y),
    mix(mix(hash13(i + vec3(0.0, 0.0, 1.0)), hash13(i + vec3(1.0, 0.0, 1.0)), u.x),
        mix(hash13(i + vec3(0.0, 1.0, 1.0)), hash13(i + vec3(1.0, 1.0, 1.0)), u.x), u.y), u.z);
}

float fbm2(vec2 p, int octaves) {
  float sum = 0.0;
  float amp = 0.5;
  for (int i = 0; i < octaves; i++) {
    sum += amp * noise2(p);
    p = p * 2.02 + vec2(13.7, 7.1);
    amp *= 0.5;
  }
  return sum;
}

float fbm3(vec3 p, int octaves) {
  float sum = 0.0;
  float amp = 0.5;
  for (int i = 0; i < octaves; i++) {
    sum += amp * noise3(p);
    p = p * 2.03 + vec3(17.1, 9.2, 3.7);
    amp *= 0.5;
  }
  return sum;
}

float focal() { return uFocal; }
vec2 screenQ(vec2 fragCoord) { return (fragCoord - 0.5 * uResolution) / uResolution.y; }
// Page-swipe parallax, a slow ~30 s camera sway and the impact shake, as a shift of q; larger amounts read as
// nearer layers. tools/earth-view.js visibleBounds() must cover the same range.
vec2 parallaxShift(float amount) { return uCameraShift * amount; }
vec3 cameraRay(vec2 q) { return normalize(vec3(q, -focal())); }
vec2 project(vec3 p) { return p.xy * (focal() / -p.z); }

vec3 earthCenter() { return uEarthCenter; }

// Distance along unit ray rd (from the origin) to sphere (c, r); -1 on a miss.
float raySphere(vec3 rd, vec3 c, float r) {
  float b = dot(rd, c);
  float h = b * b - dot(c, c) + r * r;
  if (h < 0.0) return -1.0;
  return b - sqrt(h);
}

vec3 impactWorld() { return uImpactWorld; }
vec3 impactNormal() { return uImpactNormal; }

// State-machine progress p → position. Squared so the lock-screen hold (p ≈ 0.85) stays
// visibly short of the surface and the dive covers the last stretch.
vec3 meteorWorld(float p) { return mix(uMeteorStart, impactWorld(), p * p); }
vec3 meteorVelocityDir() { return uMeteorVel; }
