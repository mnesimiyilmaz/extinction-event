// Shared by fx.frag, meteor.frag and chunks.vert/.frag (include after common.glsl): fire colours,
// premultiplied compositing, the ray-marched rock and the state of each chunk lane.

uniform float uMeteorP;
uniform float uMeteorT;
uniform float uMeteorRadius;
uniform vec3 uFireColor;
uniform vec3 uSmokeColor;
uniform vec3 uSunDir;

const int MAX_CHUNKS = 24;

// 0 = dull red, 0.5 = uFireColor, 1 = white-hot. HDR.
vec3 fire(float heat) {
  heat = clamp(heat, 0.0, 1.0);
  vec3 c = mix(vec3(0.4, 0.03, 0.0), uFireColor, smoothstep(0.0, 0.5, heat));
  c = mix(c, vec3(1.0, 0.95, 0.85), smoothstep(0.5, 1.0, heat));
  return c * (heat * heat * 12.0 + heat * 1.5);
}

// Premultiplied "over": a on top of b.
vec4 over(vec4 a, vec4 b) { return vec4(a.rgb + b.rgb * (1.0 - a.a), a.a + b.a * (1.0 - a.a)); }

// Unit vector perpendicular to axis, at angle a around it.
vec3 around(vec3 axis, float a) {
  vec3 b1 = normalize(cross(axis, abs(axis.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0)));
  vec3 b2 = cross(axis, b1);
  return b1 * cos(a) + b2 * sin(a);
}

vec2 screenPos(vec3 p) { return project(p) - parallaxShift(uParallaxEarth); }
float screenRadius(vec3 p, float rw) { return rw * focal() / -p.z; }
vec2 screenDir(vec3 p, vec3 dir) { return normalize(project(p + dir * 0.01) - project(p)); }

// Asteroid shape in rock units (radius ~1): a lumpy "potato" plus finer surface detail.
float rockLumps(vec3 x, float seed) { return length(x) - 1.0 - (fbm3(x * 1.3 + seed, 3) - 0.5) * 0.45; }
float rockDetail(vec3 x, float seed, int octaves) { return (fbm3(x * 4.0 + seed * 1.7, octaves) - 0.5) * 0.12; } // |·| ≤ 0.06
float rockSdf(vec3 x, float seed, int octaves) { return rockLumps(x, seed) - rockDetail(x, seed, octaves); }

mat3 rotation(vec3 axis, float a) {
  axis = normalize(axis);
  float s = sin(a);
  float c = cos(a);
  float oc = 1.0 - c;
  return mat3(oc * axis.x * axis.x + c, oc * axis.x * axis.y + axis.z * s, oc * axis.z * axis.x - axis.y * s,
              oc * axis.x * axis.y - axis.z * s, oc * axis.y * axis.y + c, oc * axis.y * axis.z + axis.x * s,
              oc * axis.z * axis.x + axis.y * s, oc * axis.y * axis.z - axis.x * s, oc * axis.z * axis.z + c);
}

mat3 rockRotation(float seed, float tumble) {
  return rotation(vec3(0.3 + hash11(seed), 0.7, hash11(seed + 1.0) - 0.5), tumble);
}

// Ray-marches the rock of world radius rw centred at `center`, turned by R. On a hit returns true
// with x = the hit point in rock space. With clipGround, what lies under the Earth's surface is
// buried and never hit.
bool rockMarch(vec3 rd, vec3 center, float rw, mat3 R, float seed, bool clipGround, int steps, int octaves,
               out vec3 x) {
  x = vec3(0.0);
  float t = raySphere(rd, center, rw * 1.3);
  if (t < 0.0) return false;
  float tEnd = t + rw * 2.6;
  // Past the ground everything is buried: stop there instead of marching on and discarding it.
  float tg = clipGround ? raySphere(rd, earthCenter(), 1.0) : -1.0;
  if (tg > 0.0) tEnd = min(tEnd, tg);
  for (int i = 0; i < 64; i++) {
    if (i >= steps) break;
    x = R * ((rd * t - center) / rw);
    // Far from the surface the detail cannot matter yet: step on the lumps alone (3 noise
    // lookups instead of 3 + octaves), less the most the detail could add.
    float lumps = rockLumps(x, seed);
    float d = (lumps > 0.2 ? lumps - 0.06 : lumps - rockDetail(x, seed, octaves)) * rw;
    if (d < rw * 0.003) return !(tg > 0.0 && tg < t); // the ground in front: buried part
    t += d * 0.75; // the displaced SDF overestimates distance; under-step
    if (t > tEnd) break;
  }
  return false;
}

// Camera-space surface normal at rock-space point x.
vec3 rockNormal(vec3 x, mat3 R, float seed, int octaves) {
  vec2 e = vec2(1.0, -1.0) * 0.015;
  vec3 nl = normalize(e.xyy * rockSdf(x + e.xyy, seed, octaves) + e.yyx * rockSdf(x + e.yyx, seed, octaves)
                    + e.yxy * rockSdf(x + e.yxy, seed, octaves) + e.xxx * rockSdf(x + e.xxx, seed, octaves));
  return transpose(R) * nl;
}

float rockTone(vec3 x, float seed) { return fbm3(x * 6.0 + seed, 3); }

// Lights a hit at rock-space x (normal n, tone) seen along rd. The face toward `dir` (direction of
// travel) ablates white-hot; cavities smoulder. With clipGround a molten ring glows along the
// contact line with the ground.
vec4 rockShade(vec3 rd, vec3 center, float rw, mat3 R, vec3 x, vec3 n, float tone, vec3 dir, float heat,
               float seed, bool clipGround) {
  vec3 xw = transpose(R) * x; // world-oriented, so the flow follows the travel direction
  vec3 pw = center + xw * rw;
  vec3 albedo = mix(vec3(0.045, 0.04, 0.035), vec3(0.15, 0.13, 0.11), tone);
  vec3 col = albedo * (max(dot(n, normalize(uSunDir)), 0.0) * 2.5 + 0.03);
  // Ablation spread over the whole body: hottest facing the direction of travel, with molten
  // patches that flow from the leading face toward the back.
  float facing = dot(n, dir);
  float hotness = smoothstep(-0.7, 1.0, facing);
  float flow = fbm3(xw * 3.0 - dir * uTime * 1.5 + seed, 4);
  float molten = smoothstep(0.46, 0.76, flow) * hotness;
  col += fire(heat * (0.3 + 0.55 * hotness) * (0.7 + 0.4 * flow)) * molten;
  // Plasma sheath: glows at grazing angles all around the silhouette, strongest at the front.
  float graze = 1.0 - clamp(dot(n, -rd), 0.0, 1.0);
  float rim = graze * graze * graze;
  col += fire(0.6 * heat) * rim * (0.35 + 0.65 * hotness) * heat;
  // The deepest cavities smoulder.
  float crevice = (1.0 - smoothstep(0.26, 0.32, tone)) * hotness;
  col += fire(0.45 * heat) * crevice * heat * 0.2;
  if (clipGround) {
    float h = length(pw - earthCenter()) - 1.0;
    col += fire(0.5) * exp(-abs(h) / (0.06 * rw)) * 0.8;
  }
  return vec4(col, 1.0);
}

// Ray-marched rock of world radius rw centred at `center`, tumbling by angle `tumble`.
vec4 rock3d(vec2 q, vec3 center, float rw, vec3 dir, float heat, float seed, float tumble,
            bool clipGround, int steps, int octaves) {
  if (rw <= 0.0) return vec4(0.0);
  vec3 rd = cameraRay(q + parallaxShift(uParallaxEarth));
  mat3 R = rockRotation(seed, tumble);
  vec3 x;
  if (!rockMarch(rd, center, rw, R, seed, clipGround, steps, octaves, x)) return vec4(0.0);
  return rockShade(rd, center, rw, R, x, rockNormal(x, R, seed, octaves), rockTone(x, seed), dir, heat, seed,
                   clipGround);
}

// After impact the meteor lies well over half buried and no longer moves or turns, so its geometry
// is cached (rockgeo.frag) with the background and only shaded per frame. The cache holds
// ROCK_CACHE² texels, one per render pixel, centred on the rock's current screen position.
const float ROCK_CACHE = 256.0;
vec3 buriedRockCenter() { return meteorWorld(uMeteorP) - impactNormal() * uMeteorRadius * 0.6; }
mat3 buriedRockRotation() { return rockRotation(0.0, uMeteorT * 0.25); }
// uResolution: the scene's. Larger rocks (other screens, scales) are marched directly.
bool rockCacheFits() {
  return 1.4 * screenRadius(buriedRockCenter(), uMeteorRadius) * uResolution.y < 0.5 * ROCK_CACHE - 1.0;
}

// Short fiery streak trailing a small body moving along screen direction `ahead`.
vec3 streak(vec2 q, vec3 p, float r, vec2 ahead, float len, float heat) {
  if (r <= 0.0 || len <= 0.0) return vec3(0.0);
  vec2 d = q - screenPos(p);
  float behind = -dot(d, ahead);
  if (behind < 0.0) return vec3(0.0);
  float across = dot(d, vec2(-ahead.y, ahead.x));
  float w = r * (0.7 + behind / len);
  return fire(heat * exp(-behind / len)) * exp(-sq(across / w) - behind / len * 2.5);
}

// Smaller rocks fly alongside the meteor and carry on after the impact; then each lane keeps
// sending new ones in from off-screen down the same line. Most burn away before the ground, a few
// come down with a small flash. Only the main meteor makes a real crater.
struct Chunk {
  vec3 p;       // camera-space centre
  float rw;     // world radius; 0 = nothing to draw (not emerged yet / burnt up)
  float heat;
  float tumble;
  bool lands;   // reaches the ground (with clipping and a flash) instead of burning up
  vec3 endP;    // where this lane comes down
  float ph;     // seconds since the lane's last arrival; < 0 = none yet
  float burstR; // world radius of the landing flash
};

// Everything about chunk i that does not depend on the pixel. Valid while uMeteorT >= 0.
Chunk chunkAt(int i) {
  Chunk c;
  bool struck = uImpactT >= 0.0;
  vec3 v = meteorVelocityDir();
  vec3 impact = impactWorld();
  float fi = float(i);
  float h1 = hash11(fi * 3.17 + 0.5), h2 = hash11(fi * 5.31 + 1.7), h3 = hash11(fi * 7.77 + 2.9);
  float h4 = hash11(fi * 9.13 + 4.1), h5 = hash11(fi * 11.9 + 6.3);
  c.rw = uMeteorRadius * mix(0.1, 0.24, h1);
  c.burstR = c.rw;
  vec3 side = around(v, h5 * 6.2831853);
  c.lands = struck && h4 > 0.6; // about a third of the lanes reach the ground
  c.endP = vec3(0.0);
  c.ph = -1.0;
  if (!struck) {
    float wobble = sin(uMeteorT * (0.6 + h4) + h3 * 6.28) * 0.4;
    c.p = meteorWorld(uMeteorP) - v * uMeteorRadius * (2.0 + 6.0 * h2)
        + side * uMeteorRadius * (1.6 + 2.5 * h3 + wobble);
    c.heat = 0.6 + 0.3 * h4;
    c.tumble = uMeteorT * (0.5 + h4);
  } else {
    // Keep flying from where the chunk was at impact, along the meteor's line at its own pace.
    // After that the lane sends a new chunk in from far up the line, off-screen, now and then.
    float wobble = sin(uMeteorT * (0.6 + h4) + h3 * 6.28) * 0.4;
    vec3 x0 = impact - v * uMeteorRadius * (2.0 + 6.0 * h2) + side * uMeteorRadius * (1.6 + 2.5 * h3 + wobble);
    float R = c.lands ? 1.0 : 1.0 + mix(0.008, 0.02, h5); // ground, or the altitude where it burns up
    vec3 rel = x0 - earthCenter();
    float b = dot(rel, v);
    float disc = b * b - dot(rel, rel) + R * R;
    float sEnd = disc > 0.0 ? max(-b - sqrt(disc), 0.0) : 0.05;
    c.endP = x0 + v * sEnd;
    float speed = mix(0.06, 0.1, h1);
    const float L = 0.6; // far enough up the line to be off-screen
    float gap = mix(0.5, 3.0, h2);
    float period = L / speed + gap;
    float d0 = sEnd / speed; // when the chunk that was in flight at impact arrives
    float u = uImpactT;
    float traveled;
    float total;
    if (u < d0) {
      c.p = x0 + v * speed * u;
      traveled = speed * u;
      total = sEnd;
    } else {
      c.ph = mod(u - d0, period);
      traveled = max(c.ph - gap, 0.0) * speed;
      total = L;
      c.p = c.endP - v * (L - traveled);
    }
    float progress = traveled / max(total, 1e-4);
    if (!c.lands) c.rw *= 1.0 - smoothstep(0.8, 1.0, progress); // burns away before the ground
    c.heat = 0.6 + 0.4 * progress;
    c.tumble = uMeteorT * (0.5 + h4) + u * (0.5 + h4);
  }
  return c;
}
