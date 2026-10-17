#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// The meteor with its trail, and after impact the half-buried rock. Blended premultiplied over the
// impact layer and the chunks behind it; chunks in front of it, the particles and the flash follow.

uniform vec3 uMeteorHeatColor;
uniform sampler2D uRockPos;    // buried rock geometry cache (rockgeo.frag): rock-space hit point, w = hit
uniform sampler2D uRockNormal; // camera-space normal, w = tone

flat in vec3 vMeteor;   // see meteor.vert
flat in vec3 vVelocity;
flat in vec4 vHead;
flat in vec4 vTrail;
flat in vec3 vSunk;
flat in vec4 vRock;
out vec4 fragColor;

vec4 meteor(vec2 q) {
  if (uMeteorT < 0.0) return vec4(0.0);
  bool struck = uImpactT >= 0.0;
  vec3 m = vMeteor;
  vec3 v = vVelocity;
  vec2 mq = vHead.xy;
  float r = vHead.z;
  float heat = vHead.w;
  vec2 ahead = vTrail.xy;
  float len = vTrail.z;
  float linger = vTrail.w;
  vec2 side = vec2(-ahead.y, ahead.x);
  vec2 d = q - mq;
  float dist = length(d);
  float behind = -dot(d, ahead);
  float across = dot(d, side);

  float w = r * (0.8 + max(behind, 0.0) / len * 3.0);
  // Beyond 2.5 trail lengths, or 2.5 widths (5 r near the head, for the core) aside, every term of
  // the trail is below 1e-4.
  bool inTrail = behind > 0.0 && behind < 2.5 * len && abs(across) < max(2.5 * w, 5.0 * r);
  // Bow shock fades to < 0.1% beyond ~3.5 r; nothing else reaches further from the head.
  if (!inTrail && dist > r * 4.0) return vec4(0.0);

  vec3 col = vec3(0.0);
  float alpha = 0.0;
  if (inTrail) {
    float turb = fbm2(vec2(behind * 40.0 - uTime * 6.0, across * 80.0), 4);
    float profile = exp(-sq(across / w) * (1.5 + 2.0 * turb));
    float fade = exp(-behind / len * 3.0);
    // Wake as wide as the rock, not a narrow jet from its centre.
    float core = exp(-sq(across / (r * 1.4)) - behind / (len * 0.2));
    // Taper in behind the rock: a full-strength start at its centre leaves a hard step beside it.
    float glow = (struck ? linger * 0.6 : 1.0) * smoothstep(0.0, 1.5 * r, behind);
    col += fire(fade * heat * (0.5 + 0.7 * turb)) * profile * fade * glow;
    col += uMeteorHeatColor * core * heat * 1.0 * glow;
    float smoke = profile * smoothstep(0.1, 0.6, behind / len) * fade * turb * 0.6 * linger;
    col += uSmokeColor * smoke;
    alpha = smoke;
  }

  vec4 body;
  if (!struck) {
    float front = smoothstep(-r, r, -behind);
    float flicker = 0.65 + 0.35 * fbm2(normalize(d + 1e-6) * 3.0 + vec2(uTime * 4.0, 0.0), 3);
    float env = exp(-max(dist - r * 1.05, 0.0) / (r * 0.3)) * (0.45 + 0.55 * front) * flicker;
    col += uMeteorHeatColor * env * heat * 2.0;
    body = rock3d(q, m, uMeteorRadius, v, heat, 0.0, uMeteorT * 0.25, false, 48, 4);
  } else {
    // Half-buried and still burning.
    float t = uImpactT;
    float embers = mix(1.0, 0.6 + 0.12 * sin(uTime * 1.7), smoothstep(0.0, 2.5, t));
    vec3 sunk = vSunk;
    // Emerges from inside the fireball cloud as it cools and thins.
    float emerge = smoothstep(1.5, 3.5, t);
    body = vec4(0.0);
    if (emerge > 0.0 && vRock.z > 0.5) {
      // Sampled between texels: the cache is premultiplied by coverage (misses are 0), so the
      // filtered hit point and tone divide back out, the rock glides with the camera's sway instead
      // of stepping a whole pixel at a time, and its edges come out anti-aliased.
      vec2 uv = ((q - vRock.xy) * uResolution.y + 0.5 * ROCK_CACHE) / ROCK_CACHE;
      if (all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0)))) {
        vec4 g = textureLod(uRockPos, uv, 0.0);
        if (g.w > 1e-3) {
          vec4 gn = textureLod(uRockNormal, uv, 0.0);
          vec3 rd = cameraRay(q + parallaxShift(uParallaxEarth));
          body = rockShade(rd, sunk, uMeteorRadius, buriedRockRotation(), g.xyz / g.w, normalize(gn.xyz), gn.w / g.w,
                           v, embers, 0.0, true) * g.w;
        }
      }
    } else if (emerge > 0.0) {
      body = rock3d(q, sunk, uMeteorRadius, v, embers, 0.0, uMeteorT * 0.25, true, 48, 4);
    }
    body *= emerge;
  }
  return over(body, vec4(col, alpha));
}

void main() {
  vec2 q = screenQ(gl_FragCoord.xy);
  fragColor = meteor(q);
}
