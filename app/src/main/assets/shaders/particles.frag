#version 300 es
precision highp float;
in vec2 vCorner;
in vec3 vColor;
out vec4 fragColor;
void main() {
  // Soft elliptical sprite. Zero alpha under premultiplied blending = purely additive light.
  fragColor = vec4(vColor * exp(-dot(vCorner, vCorner) * 4.0), 0.0);
}
