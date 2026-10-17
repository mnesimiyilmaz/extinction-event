#version 300 es
precision highp float;
// Dual-filter (Kawase) bloom. uMode 0: downsample, thresholded when uPrefilter = 1.
// uMode 1: upsample, additively blended into the next larger level by the renderer.
uniform sampler2D uSource;
uniform vec2 uTexel; // 1 / source size
uniform float uMode;
uniform float uPrefilter;
uniform float uBloomThreshold;
in vec2 vUv;
out vec4 fragColor;

vec3 prefilter(vec3 c) {
  float b = max(c.r, max(c.g, c.b));
  return c * (max(b - uBloomThreshold, 0.0) / max(b, 1e-4));
}

void main() {
  vec2 h = uTexel * 0.5;
  vec3 c;
  if (uMode < 0.5) {
    c = texture(uSource, vUv).rgb * 4.0;
    c += texture(uSource, vUv + vec2(-h.x, -h.y)).rgb;
    c += texture(uSource, vUv + vec2(h.x, -h.y)).rgb;
    c += texture(uSource, vUv + vec2(-h.x, h.y)).rgb;
    c += texture(uSource, vUv + vec2(h.x, h.y)).rgb;
    c /= 8.0;
    if (uPrefilter > 0.5) c = prefilter(c);
  } else {
    c = texture(uSource, vUv + vec2(-2.0 * h.x, 0.0)).rgb;
    c += texture(uSource, vUv + vec2(2.0 * h.x, 0.0)).rgb;
    c += texture(uSource, vUv + vec2(0.0, -2.0 * h.y)).rgb;
    c += texture(uSource, vUv + vec2(0.0, 2.0 * h.y)).rgb;
    c += texture(uSource, vUv + vec2(-h.x, -h.y)).rgb * 2.0;
    c += texture(uSource, vUv + vec2(h.x, -h.y)).rgb * 2.0;
    c += texture(uSource, vUv + vec2(-h.x, h.y)).rgb * 2.0;
    c += texture(uSource, vUv + vec2(h.x, h.y)).rgb * 2.0;
    c /= 12.0;
  }
  fragColor = vec4(c, 1.0);
}
