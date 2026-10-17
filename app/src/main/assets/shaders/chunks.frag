#version 300 es
precision highp float;
//#include common.glsl
//#include bodies.glsl

// One chunk's rock and streak, or its landing flash (see chunks.vert). Premultiplied, blended over
// the impact layer; the streak and the flash are pure light.

flat in vec4 vBody;
flat in vec4 vState;
flat in vec4 vStreak;
flat in vec4 vBurst;
flat in float vRadius;
flat in int vPart;
out vec4 fragColor;

void main() {
  vec2 q = screenQ(gl_FragCoord.xy);
  if (vPart == 1) {
    float bd = length(q - vBurst.xy);
    float gr = vBurst.z;
    float ph = vBurst.w;
    fragColor = vec4(fire(0.5) * exp(-ph * 10.0) * exp(-sq(bd / gr))
                   + fire(0.35) * exp(-ph * 0.8) * 0.5 * exp(-sq(bd / (gr * 0.9))), 0.0);
    return;
  }
  vec3 p = vBody.xyz;
  float heat = vState.x;
  vec4 body = rock3d(q, p, vBody.w, meteorVelocityDir(), heat, vState.z, vState.y, vState.w > 0.5, 28, 2);
  vec3 tail = streak(q, p, vRadius * 0.7, vStreak.zw, vRadius * 6.0, heat) * 0.6;
  fragColor = vec4(body.rgb + tail, body.a);
}
