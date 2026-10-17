#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// Geometry cache of the buried rock (see buriedRockCenter), refreshed with the background: the ray
// march runs at bgRefreshHz instead of every frame. Texel i holds the render pixel at offset
// i + 0.5 - ROCK_CACHE / 2 from the rock's centre; uResolution is the scene's, not this target's.

// Misses write 0 everywhere, so filtered samples are weighted by coverage (meteor.frag).
layout(location = 0) out vec4 gPos;    // hit point in rock space, w = 1 on a hit
layout(location = 1) out vec4 gNormal; // camera-space normal, w = tone

void main() {
  gPos = vec4(0.0);
  gNormal = vec4(0.0);
  vec3 center = buriedRockCenter();
  vec2 q = screenPos(center) + (gl_FragCoord.xy - 0.5 * ROCK_CACHE) / uResolution.y;
  vec3 rd = cameraRay(q + parallaxShift(uParallaxEarth));
  mat3 R = buriedRockRotation();
  vec3 x;
  if (!rockMarch(rd, center, uMeteorRadius, R, 0.0, true, 48, 4, x)) return;
  gPos = vec4(x, 1.0);
  gNormal = vec4(rockNormal(x, R, 0.0, 4), rockTone(x, 0.0));
}
