#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// Impact layer: shockwave, ground glow, fires, crater, smoke and fireball.
// Chunks (chunks.vert/.frag) and then the meteor with the flash (meteor.frag) are drawn over it.
// Premultiplied output: rgb = emitted or lit light, a = how much of the background it hides.

uniform float uShockRadius;  // radians the shock front finally reaches
uniform float uShockTime;    // seconds; the front decelerates with this time constant
uniform vec3 uShockColor;

in vec2 vUv;
out vec4 fragColor;

vec4 impact(vec2 q) {
  if (uImpactT < 0.0) return vec4(0.0);
  float t = uImpactT;
  vec3 c = earthCenter();
  vec3 nI = impactNormal();
  vec2 iq = screenPos(impactWorld());
  vec2 d = q - iq;
  float dist = length(d);
  vec3 ground = vec3(0.0);
  vec4 haze = vec4(0.0); // premultiplied: condensation and dust bands of the shock
  float smoke = 0.0;
  vec3 smokeCol = vec3(0.0);
  // The shock front decelerates: most of uShockRadius is covered in ~3 uShockTime seconds.
  float ringR = uShockRadius * (1.0 - exp(-t / uShockTime));

  vec3 rd = cameraRay(q + parallaxShift(uParallaxEarth));
  float th = raySphere(rd, c, 1.0);
  if (th > 0.0) {
    vec3 n = normalize(rd * th - c);
    float ang = 2.0 * asin(min(length(n - nI) * 0.5, 1.0)); // great-circle distance, radians
    vec3 t1 = normalize(cross(nI, abs(nI.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 t2 = cross(nI, t1);
    vec2 local = vec2(dot(n, t1), dot(n, t2)); // tangent-plane offset, ≈ radians
    float craterR = uMeteorRadius * 1.6;      // a bit wider than the rock

    if (ang < 1.2) { // light falls off smoothly; never cut it at the shock's working radius
      // The first light floods the land for hundreds of km, then dies back to a local glow.
      ground += vec3(1.0, 0.85, 0.65) * exp(-ang / (0.04 + 0.1 * min(t, 1.0))) * exp(-t * 3.0) * 4.0;
      ground += uFireColor * exp(-ang / (0.01 + 0.025 * min(t, 3.0))) * (0.25 + 0.75 * exp(-t * 0.6)) * 1.2;
    }
    if (ang < ringR + 0.08) {

      // Shock: a thin bright front, a pale condensation band right behind it, then a dark band of
      // dust further back that lingers long after the front has faded.
      // The aftermath lasts as long as the home screen shows, so terms that have faded out skip
      // their noise: the front and condensation (< 1e-4 after ~70 s) and the glowing curtain (~35 s).
      float fade = exp(-t / (uShockTime * 1.5));
      float inside = ringR - ang;
      if (fade > 1e-4) {
        float wobble = 0.7 + 0.6 * noise2(local * 60.0 + vec2(t * 0.2));
        float front = exp(-sq((ang - ringR) / (0.0015 + 0.0015 * sqrt(t)))) * fade * wobble;
        ground += uShockColor * front * 1.6;
        float condense = smoothstep(0.0, 0.01, inside) * (1.0 - smoothstep(0.01, 0.04 + 0.02 * t, inside)) * fade * wobble;
        haze = vec4(mix(uShockColor, vec3(0.9), 0.5) * 0.2 * condense, 0.15 * condense);
      }
      float dust = smoothstep(0.02, 0.07, inside) * exp(-t / 25.0) * (0.45 + 0.55 * noise2(local * 25.0 - vec2(t * 0.05)));
      haze = over(haze, vec4(uSmokeColor * 0.4 * dust * 0.3, dust * 0.3));

      // Ejecta curtain: the cone of rock and dust thrown out of the crater, seen from above as a
      // ragged ring racing outward, glowing at first, then settling into dark radial streaks.
      vec2 dirL = local / max(length(local), 1e-5);
      float curtainR = 0.004 + 0.05 * (1.0 - exp(-t * 0.9));
      float glow = exp(-t / 3.0);
      float band = 0.0;
      if (glow > 1e-5) {
        // Ragged rather than spoked: fine angular detail, mild contrast.
        float rays = 0.6 + 0.4 * noise2(dirL * 22.0 + local * 80.0 + 4.0);
        band = exp(-sq((ang - curtainR) / (0.003 + 0.01 * sqrt(t)))) * glow * rays;
      }
      vec3 curtainCol = mix(fire(0.55), uSmokeColor * 2.0, smoothstep(0.3, 2.0, t));
      float streaks = (1.0 - smoothstep(curtainR * 0.6, curtainR, ang)) * smoothstep(craterR * 1.5, craterR * 2.5, ang)
                    * smoothstep(0.5, 2.0, t) * 0.5;
      if (streaks > 0.0) streaks *= smoothstep(0.5, 0.9, noise2(dirL * 14.0 + 1.0)); // only in their ring
      haze = over(vec4(curtainCol * band * 0.8, band * 0.6),
                  over(vec4(uSmokeColor * 0.3 * streaks, streaks), haze));

      // Fires ignited by the heat pulse, each one as the front reaches it.
      const float CELL = 0.007;
      vec2 cid = floor(local / CELL);
      float hf = hash12(cid + 17.0);
      // Sparse, clustered: only where a coarse noise says there is something to burn.
      float fuel = hf > 0.8 && ang > craterR * 2.0 ? smoothstep(0.5, 0.75, noise2(local * 40.0 + 9.0)) : 0.0;
      if (fuel > 0.0) {
        vec2 fpos = (cid + 0.5 + (vec2(hash12(cid + 3.1), hash12(cid + 8.7)) - 0.5) * 0.7) * CELL;
        float fang = length(fpos);
        float ignite = -uShockTime * log(max(1.0 - fang / uShockRadius, 1e-3)); // front arrival time
        float on = smoothstep(ignite, ignite + 0.6, t) * (1.0 - smoothstep(uShockRadius * 0.3, uShockRadius * 0.85, fang));
        float flick = 0.6 + 0.4 * sin(t * (5.0 + 6.0 * hf) + hf * 30.0);
        float size = CELL * mix(0.08, 0.16, hash12(cid + 5.5));
        float spot = exp(-sq(length(local - fpos) / size));
        ground += fire(0.3 + 0.15 * hf) * spot * on * flick * fuel * 1.5;
      }
    }

    if (ang < craterR * 2.5) {
      vec2 cl = local / craterR;
      // Crater: a molten floor, soft mottled glow that slowly breathes, fading in under the fireball.
      float craterOn = smoothstep(0.8, 2.5, t);
      float crater = 1.0 - smoothstep(craterR * 0.6, craterR, ang);
      float mottle = fbm2(cl * 5.0 + vec2(uTime * 0.04, -uTime * 0.03), 4);
      float pulse = 0.8 + 0.2 * sin(uTime * 1.1 + mottle * 5.0);
      ground += fire(0.42 * pulse * (0.55 + 0.6 * mottle)) * crater * craterOn;

      // Ejecta rim: a ragged ring of dark ash just outside the crater, lit orange on its inner edge.
      float rimOn = smoothstep(0.2, 1.0, t);
      float rimP = exp(-sq((ang - craterR * 1.3) / (craterR * 0.32))) * (0.55 + 0.45 * noise2(cl * 6.0 + 3.0));
      float rimA = rimP * 0.8 * rimOn;
      vec3 rimCol = uSmokeColor * 0.6 + fire(0.4) * exp(-max(ang - craterR, 0.0) / (craterR * 0.15)) * 0.5;

      // Smoke spreading over the crater, lit from below by the fire.
      float grow = smoothstep(0.3, 3.0, t);
      float smokeR = 0.012 + 0.03 * grow;
      float plume = fbm2(local * 120.0 + vec2(uTime * 0.05, -uTime * 0.03), 5);
      smoke = (1.0 - smoothstep(smokeR * 0.3, smokeR, ang)) * smoothstep(0.35, 0.7, plume) * grow * 0.7;
      smokeCol = uSmokeColor + uFireColor * 0.6 * (1.0 - smoothstep(0.0, 0.015, ang)) * craterOn;
      // Ash rim under the smoke: fold it into the same premultiplied layer.
      smokeCol = (smokeCol * smoke + rimCol * rimA * (1.0 - smoke)) / max(smoke + rimA * (1.0 - smoke), 1e-4);
      smoke = smoke + rimA * (1.0 - smoke);
    }
  }
  vec4 res = over(vec4(smokeCol * smoke, smoke), over(haze, vec4(ground, 0.0)));

  // Fireball: a billowing mass that swells and rolls, white-hot inside at first, cooling through
  // red embers into a dark cloud of dust and smoke that thins out over several seconds.
  float fbR = 0.035 + 0.08 * (1.0 - exp(-t * 1.0));
  float cloudFade = 1.0 - smoothstep(5.0, 10.0, t);
  if (cloudFade > 0.0 && dist < fbR * 1.4) {
    vec2 w = d / fbR + vec2(0.0, -0.08 * min(t, 4.0)); // drifts up-screen as it rises
    float warp = fbm2(w * 2.0 + vec2(0.0, -t * 0.4), 4);
    float billow = fbm2(w * 3.5 + warp * 1.5 + vec2(t * 0.2, -t * 0.5), 5);
    float rr = length(w) - 0.35 * (billow - 0.5);       // soft lumpy edge, no spikes
    float body = 1.0 - smoothstep(0.65, 1.05, rr);
    float core = 1.0 - smoothstep(0.0, 0.85, rr);
    float heat = core * exp(-t * 0.7) * (0.6 + 0.7 * billow) * 1.3;
    float sooty = smoothstep(0.4, 2.5, t);               // the hot plasma turns to smoke
    float alpha = body * sooty * 0.75 * cloudFade;
    vec3 smokeLit = uSmokeColor * (0.5 + billow) + fire(0.35) * core * exp(-t * 0.25) * 0.6;
    vec3 glow = fire(heat) * body * cloudFade;
    res = over(vec4(smokeLit * alpha + glow, alpha), res);
  }
  return res;
}

void main() {
  fragColor = impact(screenQ(gl_FragCoord.xy));
}
