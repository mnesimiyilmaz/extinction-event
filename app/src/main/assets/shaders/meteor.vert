#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// Full-screen triangle (as fullscreen.vert) that also works out what meteor.frag needs from the
// uniforms alone: once per vertex instead of once per pixel.

uniform float uTrailLength;

flat out vec3 vMeteor;   // camera-space centre
flat out vec3 vVelocity; // direction of travel
flat out vec4 vHead;     // screen centre, screen radius, heat
flat out vec4 vTrail;    // screen direction of travel, trail length, linger
flat out vec3 vSunk;     // buried rock centre (after impact)
flat out vec4 vRock;     // its screen centre, 1 when its geometry cache is used, unused

void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);

  bool struck = uImpactT >= 0.0;
  vec3 m = meteorWorld(uMeteorP);
  vec3 v = meteorVelocityDir();
  vMeteor = m;
  vVelocity = v;
  float alt = length(m - earthCenter()) - 1.0;
  float heat = 0.3 + 0.7 * (1.0 - smoothstep(0.0, 0.12, alt)); // air thickens near the surface
  vHead = vec4(screenPos(m), screenRadius(m, uMeteorRadius), heat);
  // After impact the trail fades to a lingering smoky streak instead of vanishing.
  float linger = struck ? 0.25 + 0.75 * exp(-uImpactT * 1.5) : 1.0;
  vTrail = vec4(screenDir(m, v), uTrailLength * (0.6 + 0.8 * heat), linger);
  vSunk = buriedRockCenter();
  vRock = vec4(screenPos(vSunk), rockCacheFits() ? 1.0 : 0.0, 0.0);
}
