#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

in vec2 vUv;
out vec4 fragColor;

// Flash: a blinding point, a moment of whiteout over the whole screen, a lingering bloom of light
// around the impact, and a horizontal lens streak. Drawn last and added (pure light), over
// everything: chunks and particles included. Negligible (< 1e-4) after 5 s.
vec3 impactFlash(vec2 q) {
  if (uImpactT < 0.0) return vec3(0.0);
  float t = uImpactT;
  vec2 d = q - screenPos(impactWorld());
  float dist = length(d);
  float flash = exp(-t * 14.0);
  return vec3(1.0, 0.96, 0.9) * (flash * (40.0 * exp(-dist * dist / 0.003) + 2.5) + exp(-t * 1.5) * 0.15 * exp(-dist / 0.3))
       + vec3(1.0, 0.8, 0.6) * exp(-sq(d.y / 0.004) - abs(d.x) / 0.18) * exp(-t * 2.5) * 3.0;
}

void main() {
  fragColor = vec4(impactFlash(screenQ(gl_FragCoord.xy)), 0.0);
}
