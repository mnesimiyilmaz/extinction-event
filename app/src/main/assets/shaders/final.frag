#version 300 es
precision highp float;
//#include common.glsl

// Composite: heat-distorted background under the premultiplied dynamic layer,
// then exposure, ACES tonemap, vignette, gamma and dither.

uniform sampler2D uBackground;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength;
uniform float uMeteorP;
uniform float uMeteorT;
uniform float uMeteorRadius;
uniform float uDistortion;
uniform float uExposure;
uniform float uVignette;
uniform vec2 uBgCameraShift; // uCameraShift when the background was last rendered

in vec2 vUv;
out vec4 fragColor;

vec3 aces(vec3 x) {
  return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Heat shimmer behind the meteor: a background sampling offset, in height units.
vec2 heatOffset(vec2 q) {
  if (uMeteorT < 0.0) return vec2(0.0);
  float fade = uImpactT >= 0.0 ? exp(-uImpactT * 2.0) : 1.0;
  vec3 m = meteorWorld(uMeteorP);
  vec2 mq = project(m) - parallaxShift(uParallaxEarth);
  vec2 back = normalize(project(m - meteorVelocityDir() * 0.05) - project(m));
  float r = uMeteorRadius * focal() / -m.z;
  vec2 d = q - mq;
  float along = dot(d, back);
  float across = dot(d, vec2(-back.y, back.x));
  float mask = smoothstep(-r, r, along) * exp(-sq(across / (r * 2.5)) - max(along, 0.0) / (r * 12.0));
  if (mask * fade < 1e-3) return vec2(0.0);
  vec2 wobble = vec2(noise2(q * 60.0 + vec2(0.0, uTime * 4.0)),
                     noise2(q * 60.0 + vec2(5.2, 1.3 - uTime * 4.0))) - 0.5;
  return wobble * mask * fade * uDistortion;
}

void main() {
  vec2 q = screenQ(gl_FragCoord.xy);
  // Between its refreshes the cached background is moved with the camera, so the Earth glides with
  // the sway every frame like the layers drawn over it (a shift of q is a pure translation).
  vec2 drift = (uCameraShift - uBgCameraShift) * uParallaxEarth;
  vec2 off = (heatOffset(q) + drift) * vec2(uResolution.y / uResolution.x, 1.0);
  vec3 bg = texture(uBackground, vUv + off).rgb;
  vec4 scene = texture(uScene, vUv);
  vec3 col = bg * (1.0 - scene.a) + scene.rgb;
  col += texture(uBloom, vUv).rgb * uBloomStrength;
  col = aces(col * uExposure);
  vec2 v = vUv - 0.5;
  col *= 1.0 - uVignette * dot(v, v) * 2.0;
  col = pow(col, vec3(1.0 / 2.2));
  col += (hash12(gl_FragCoord.xy + fract(uTime) * 100.0) - 0.5) / 255.0; // dither against banding
  fragColor = vec4(col, 1.0);
}
