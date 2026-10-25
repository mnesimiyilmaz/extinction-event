#version 300 es
precision highp float;
//#include common.glsl

// Instanced sparks. Each particle is an analytic function of its index and the
// frame uniforms; nothing is simulated or stored, so scrubbing is exact.
// uKind 0: fragments shed by the meteor, 1: impact ejecta, 2: aftermath embers.

uniform float uMeteorP;
uniform float uMeteorT;
uniform float uImpactDuration;
uniform float uMeteorRadius;
uniform float uEjectaSpeed;
uniform float uKind;
uniform vec3 uFireColor;
uniform vec3 uMeteorHeatColor;

layout(location = 0) in vec2 aCorner; // quad corner, -1..1
out vec2 vCorner;
out vec3 vColor;

struct Particle {
  vec3 pos;      // camera space
  vec3 dir;      // world direction the streak is drawn along
  float size;    // world radius
  float stretch; // streak length / width
  vec3 color;    // HDR
  bool alive;
};

float h(float id, float k) { return hash11(id * 7.31 + k * 1.73); }

// Unit vector perpendicular to axis, at angle a around it.
vec3 around(vec3 axis, float a) {
  vec3 b1 = normalize(cross(axis, abs(axis.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0)));
  vec3 b2 = cross(axis, b1);
  return b1 * cos(a) + b2 * sin(a);
}

Particle fragmentAt(float id) {
  Particle P;
  float life = mix(1.2, 2.6, h(id, 1.0));
  float age = fract(uMeteorT / life + h(id, 2.0)) * life;
  float k = age / life;
  vec3 v = meteorVelocityDir();
  vec3 side = around(v, h(id, 3.0) * 2.0 * PI);
  // Shed from all around the silhouette and spreading sideways, not from one point at the back
  // (that reads as a rocket plume). No warm-up: the stream looks settled from the first frame.
  P.pos = meteorWorld(uMeteorP) + side * uMeteorRadius * mix(0.7, 1.1, h(id, 7.0))
        - v * (uMeteorRadius * 0.3 + mix(0.02, 0.06, h(id, 4.0)) * age)
        + side * mix(0.004, 0.02, h(id, 5.0)) * age;
  P.dir = v;
  P.size = uMeteorRadius * mix(0.03, 0.08, h(id, 6.0));
  P.stretch = 4.0;
  P.color = mix(uMeteorHeatColor * 4.0, uFireColor * 1.5, k) * sq(1.0 - k) * smoothstep(0.0, 0.1, age);
  // Sparks crossing in front of the rock would wash out its face.
  vec3 m = meteorWorld(uMeteorP);
  float dm = length(project(P.pos) - project(m)) / (uMeteorRadius * focal() / -m.z);
  P.color *= smoothstep(0.9, 1.3, dm);
  P.alive = uImpactT < 0.0 && length(P.pos - earthCenter()) > 1.0;
  return P;
}

Particle ejectaAt(float id) {
  Particle P;
  vec3 nI = impactNormal();
  // A handful of small sparks thrown out of the fireball over its first second, arcing down.
  float t = uImpactT - mix(0.15, 1.0, h(id, 1.0));
  float life = mix(1.5, 3.5, h(id, 2.0));
  float k = t / life;
  vec3 tang = around(nI, h(id, 3.0) * 2.0 * PI);
  vec3 vel0 = (tang * mix(0.015, 0.04, h(id, 4.0)) + nI * mix(0.02, 0.045, h(id, 5.0))) * uEjectaSpeed;
  const float g = 0.03;
  P.pos = impactWorld() + vel0 * t - nI * (0.5 * g * t * t);
  vec3 vel = vel0 - nI * (g * t);
  P.dir = length(vel) > 1e-6 ? normalize(vel) : nI;
  P.size = uMeteorRadius * mix(0.03, 0.06, h(id, 6.0));
  P.stretch = 2.0 + length(vel) * 50.0;
  P.color = mix(vec3(1.0, 0.75, 0.4) * 3.0, uFireColor * 1.5, smoothstep(0.0, 0.4, k)) * pow(max(1.0 - k, 0.0), 1.5)
          * smoothstep(0.0, 0.1, t);
  P.alive = t > 0.0 && k < 1.0 && length(P.pos - earthCenter()) > 0.9995;
  return P;
}

Particle emberAt(float id) {
  Particle P;
  vec3 nI = impactNormal();
  float tt = uImpactT - uImpactDuration * 0.6;
  float life = mix(4.0, 7.0, h(id, 1.0));
  float age = fract(tt / life + h(id, 2.0)) * life;
  vec3 tang = around(nI, h(id, 3.0) * 2.0 * PI);
  float wander = age * 0.004 * h(id, 4.0) + 0.004 * sin(age * 1.3 + h(id, 5.0) * 6.28);
  P.pos = impactWorld() + nI * age * mix(0.003, 0.008, h(id, 6.0)) + tang * wander;
  P.dir = nI;
  P.size = uMeteorRadius * mix(0.03, 0.07, h(id, 7.0));
  P.stretch = 1.5;
  float flicker = 0.7 + 0.3 * sin(uTime * 9.0 + id * 1.7);
  P.color = uFireColor * 2.5 * sin(PI * age / life) * flicker;
  P.alive = tt > 0.0 && age < tt;
  return P;
}

void main() {
  float id = float(gl_InstanceID);
  Particle P;
  if (uKind < 0.5) {
    P = fragmentAt(id);
  } else if (uKind < 1.5) {
    P = ejectaAt(id);
  } else {
    P = emberAt(id);
  }
  vCorner = aCorner;
  vColor = P.color;
  if (!P.alive || P.pos.z > -0.01) {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // outside the clip volume: culled
    return;
  }
  vec2 c = project(P.pos) - parallaxShift(uParallaxEarth);
  float r = max(P.size * focal() / -P.pos.z, 1.5 / uResolution.y); // keep tiny sparks visible
  vec2 dir = project(P.pos + P.dir * 0.01) - project(P.pos);
  dir = length(dir) > 1e-6 ? normalize(dir) : vec2(1.0, 0.0);
  vec2 q = c + dir * (aCorner.x * r * P.stretch) + vec2(-dir.y, dir.x) * (aCorner.y * r);
  gl_Position = vec4(q * vec2(uResolution.y / uResolution.x, 1.0) * 2.0, 0.0, 1.0);
}
