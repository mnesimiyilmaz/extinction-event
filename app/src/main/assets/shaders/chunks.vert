#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// Chunks as instanced quads: instance 2i bounds chunk i's rock and streak, 2i+1 its landing flash.
// The lane state is worked out once per vertex here instead of once per pixel per chunk, and the
// ray march runs only inside the quad. Instances draw in order, so the layering matches a loop.

uniform float uChunkLayer; // 0: chunks behind the buried rock (all of them before impact), 1: in front

layout(location = 0) in vec2 aCorner; // quad corner, -1..1

flat out vec4 vBody;   // centre (camera space), world radius
flat out vec4 vState;  // heat, tumble, seed, lands
flat out vec4 vStreak; // screen centre, screen direction of travel
flat out vec4 vBurst;  // screen centre, screen radius, seconds since landing
flat out float vRadius;
flat out int vPart;

// q (height units, origin at the centre) → clip space.
vec4 clipAt(vec2 q) { return vec4(q * 2.0 * uResolution.y / uResolution, 0.0, 1.0); }

void main() {
  int i = gl_InstanceID / 2;
  vPart = gl_InstanceID - i * 2;
  gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // outside the clip volume: nothing to draw
  if (uMeteorT < 0.0) return;
  Chunk c = chunkAt(i);
  // After impact, chunks nearer the camera than the half-buried rock are drawn after it.
  bool front = uImpactT >= 0.0 && (vPart == 0 ? c.p.z : c.endP.z) > buriedRockCenter().z;
  if (front != (uChunkLayer > 0.5)) return;
  vBody = vec4(c.p, c.rw);
  vState = vec4(c.heat, c.tumble, float(i) * 1.37 + 0.2, c.lands ? 1.0 : 0.0);
  if (vPart == 0) {
    if (c.rw <= 0.0) return; // a zero-length streak would be 0/0
    float r = screenRadius(c.p, c.rw);
    vec2 sp = screenPos(c.p);
    vec2 ahead = screenDir(c.p, meteorVelocityDir());
    vStreak = vec4(sp, ahead);
    vRadius = r;
    // The rock fits in 1.3 r; the streak fades out within ~20 r behind and 9 r across.
    float along = mix(-20.0 * r, 1.5 * r, aCorner.x * 0.5 + 0.5);
    gl_Position = clipAt(sp + ahead * along + vec2(-ahead.y, ahead.x) * 9.0 * r * aCorner.y);
  } else {
    if (!c.lands || c.ph < 0.0) return;
    float gr = screenRadius(c.endP, c.burstR);
    vBurst = vec4(screenPos(c.endP), gr, c.ph);
    gl_Position = clipAt(vBurst.xy + aCorner * 3.5 * gr);
  }
}
