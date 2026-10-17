#version 300 es
precision highp float;
//#include common.glsl

// Cached background: stars, Earth, clouds, atmosphere.
// Re-rendered at bgRefreshHz and on page-offset changes, not every frame.

uniform vec3 uSunDir;
uniform vec2 uImpactLatLon;   // degrees (lat, lon) of the impact point on the texture
uniform float uNorthHeading;  // degrees; 0 = north points up-screen at the impact point
uniform vec4 uEarthCrop;      // (lon0, lon1, lat0, lat1) degrees covered by the textures
uniform float uCloudWind;     // eastward cloud drift, degrees per second
uniform float uCloudCycle;    // seconds per flow cycle; offsets stay within ±wind·cycle/2
uniform float uShockRadius;   // shared with fx.frag: where the shock front is at uImpactT
uniform float uShockTime;
uniform float uNightLights;
uniform vec3 uOceanColor;
uniform vec3 uCityColor;
uniform vec3 uAtmoColor;
uniform vec3 uTwilightColor;
uniform float uParallaxStars;
uniform float uStarDensity;
uniform sampler2D uEarthDay;  // sRGB colour (NASA Blue Marble), sampled as linear
uniform sampler2D uEarthAux;  // R night lights, G clouds, B water mask

in vec2 vUv;
out vec4 fragColor;

// Camera-space surface normal -> (lon, lat) in degrees. Mirrors tools/earth-view.js.
vec2 earthLonLat(vec3 n) {
  vec3 nI = impactNormal();
  vec3 N = normalize(vec3(0.0, 1.0, 0.0) - nI * nI.y);
  float th = radians(uNorthHeading);
  N = N * cos(th) + cross(nI, N) * sin(th);
  vec3 E = cross(N, nI);
  vec2 ll = radians(uImpactLatLon);
  vec3 e0 = vec3(cos(ll.x) * cos(ll.y), cos(ll.x) * sin(ll.y), sin(ll.x));
  vec3 east = vec3(-sin(ll.y), cos(ll.y), 0.0);
  vec3 north = vec3(-sin(ll.x) * cos(ll.y), -sin(ll.x) * sin(ll.y), cos(ll.x));
  vec3 v = east * dot(n, E) + north * dot(n, N) + e0 * dot(n, nI);
  return degrees(vec2(atan(v.y, v.x), asin(clamp(v.z, -1.0, 1.0))));
}

// Texture gradients of the Earth's uv, taken in main() in uniform control flow: the Earth is
// shaded inside a branch, where implicit derivatives (and so mip selection) are undefined.
vec2 gUvDx;
vec2 gUvDy;
vec4 earthTexture(sampler2D s, vec2 uv) { return textureGrad(s, uv, gUvDx, gUvDy); }

// Clouds at (lon, lat), advected by the wind without ever leaving the crop: two samples whose
// offsets each cycle over uCloudCycle, half a cycle apart, cross-faded (flow-map technique).
float cloudsAt(vec2 lonLat);

// (lon, lat) -> texture uv; row 0 of the image is the crop's northern edge.
vec2 cropUv(vec2 lonLat) {
  return vec2((lonLat.x - uEarthCrop.x) / (uEarthCrop.y - uEarthCrop.x),
              (uEarthCrop.w - lonLat.y) / (uEarthCrop.w - uEarthCrop.z));
}

float cloudsAt(vec2 lonLat) {
  float ph0 = fract(uTime / uCloudCycle);
  float ph1 = fract(uTime / uCloudCycle + 0.5);
  float span = uCloudWind * uCloudCycle;
  float c0 = earthTexture(uEarthAux, cropUv(lonLat - vec2(span * (ph0 - 0.5), 0.0))).g;
  float c1 = earthTexture(uEarthAux, cropUv(lonLat - vec2(span * (ph1 - 0.5), 0.0))).g;
  return mix(c0, c1, abs(ph0 - 0.5) * 2.0); // each sample fades out as its offset wraps
}

vec3 stars(vec2 q) {
  vec3 col = vec3(0.0);
  for (int layer = 0; layer < 2; layer++) {
    float scale = layer == 0 ? 90.0 : 220.0;
    float sharp = layer == 0 ? 60.0 : 90.0;
    float thresh = uStarDensity * (layer == 0 ? 0.25 : 0.12);
    vec2 cell = floor(q * scale) + float(layer) * 101.0;
    vec2 f = fract(q * scale) - 0.5;
    if (hash12(cell) > thresh) continue;
    vec2 jitter = vec2(hash12(cell + 7.3), hash12(cell + 19.1)) - 0.5;
    vec2 d = f - jitter * 0.5;
    float b = pow(hash12(cell + 3.7), 6.0) * 3.0 + 0.15;
    float h = hash12(cell + 11.0);
    float twinkle = 0.85 + 0.15 * sin(uTime * (0.3 + 0.9 * h) + h * 40.0);
    vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), h);
    col += tint * b * twinkle * exp(-dot(d, d) * sharp);
  }
  // Faint nebula wash so the space side is not flat black.
  col += vec3(0.020, 0.012, 0.035) * smoothstep(0.45, 0.85, fbm2(q * 2.5 + 3.0, 4));
  return col;
}

// Glow of the atmosphere just outside the limb, lit on the sun side.
vec3 atmosphereRim(vec3 rd, vec3 c) {
  vec3 closest = rd * max(dot(rd, c), 0.0);
  float hgt = length(closest - c) - 1.0;
  if (hgt < 0.0 || hgt > 0.08) return vec3(0.0);
  float ndl = dot(normalize(closest - c), normalize(uSunDir));
  vec3 tint = mix(uTwilightColor, uAtmoColor, smoothstep(-0.1, 0.35, ndl));
  return tint * smoothstep(-0.25, 0.25, ndl) * exp(-hgt / 0.012) * 1.6;
}

vec3 earth(vec3 rd, float t, vec3 c) {
  vec3 n = normalize(rd * t - c);
  vec3 v = -rd;
  vec3 sun = normalize(uSunDir);
  float ndl = dot(n, sun);
  float day = smoothstep(-0.05, 0.15, ndl);
  float twilight = exp(-sq(ndl / 0.05));
  float lambert = clamp(ndl * 1.1 + 0.03, 0.0, 1.0);

  vec2 ll = earthLonLat(n);
  vec2 uv = cropUv(ll);
  vec3 albedo = earthTexture(uEarthDay, uv).rgb;
  vec4 aux = earthTexture(uEarthAux, uv);
  float water = aux.b;
  // Blue Marble bathymetry is bright; real open ocean from orbit is a deep navy.
  albedo = mix(albedo, albedo * 0.35 + uOceanColor * 0.5, water);

  // Shock state here (mirrors fx.frag impact()): how far the front has already swept past.
  float ringR = uImpactT >= 0.0 ? uShockRadius * (1.0 - exp(-uImpactT / uShockTime)) : 0.0;
  float ang = 2.0 * asin(min(length(n - impactNormal()) * 0.5, 1.0));
  float swept = uImpactT >= 0.0 ? smoothstep(0.0, 0.03, ringR - ang) : 0.0;
  // The blast pushes clouds outward (sample from nearer the impact) and thins them.
  vec2 impLL = uImpactLatLon.yx;
  float push = 1.0 - 0.45 * swept;
  float cloud = cloudsAt(impLL + (ll - impLL) * push) * (1.0 - 0.5 * swept);
  vec3 toSun = normalize(sun - n * ndl);
  float shadow = cloudsAt(impLL + (earthLonLat(normalize(n + toSun * 0.004)) - impLL) * push) * (1.0 - 0.5 * swept);

  vec3 col = albedo * lambert * 2.2 * (1.0 - 0.45 * shadow);
  float glint = pow(max(dot(reflect(-sun, n), v), 0.0), 120.0);
  col += water * (1.0 - cloud) * glint * day * vec3(1.0, 0.9, 0.75) * 2.0;
  col += albedo * uTwilightColor * twilight * 0.12;

  vec3 cloudCol = vec3(lambert * 2.4) + uTwilightColor * twilight * 0.3 + vec3(0.006, 0.008, 0.014);
  col = mix(col, cloudCol, cloud * 0.95);

  // Black Marble lights: gamma-encoded over a moonlit grey floor (~0.12) that must not glow.
  float lights = pow(max(aux.r - 0.12, 0.0) / 0.88, 2.2);
  // Faint scintillation through the atmosphere, per texel cluster.
  float cell = hash12(floor(uv * 900.0));
  lights *= 0.85 + 0.15 * sin(uTime * (1.5 + 2.5 * cell) + cell * 40.0);
  // Blackout: cities go dark as the shock front reaches them, then the whole grid fails.
  float blackout = uImpactT >= 0.0 ? max(smoothstep(0.0, 0.015, ringR - ang), smoothstep(5.0, 12.0, uImpactT)) : 0.0;
  lights *= 1.0 - blackout;
  col += uCityColor * lights * (1.0 - day) * (1.0 - cloud * 0.85) * uNightLights * 3.0;

  // Atmospheric haze: strongest at grazing angles, sunset-tinted only close to the terminator.
  float graze = 1.0 - clamp(dot(n, v), 0.0, 1.0);
  float haze = graze * graze * graze;
  vec3 atmo = mix(uTwilightColor, uAtmoColor, smoothstep(-0.03, 0.12, ndl));
  col += atmo * haze * (day + twilight * 0.4) * 1.2;
  return col + vec3(0.001, 0.002, 0.005); // night ambient
}

void main() {
  vec2 q = screenQ(gl_FragCoord.xy);
  vec3 c = earthCenter();
  vec3 rd = cameraRay(q + parallaxShift(uParallaxEarth));
  float b = dot(rd, c);
  float disc = b * b - dot(c, c) + 1.0;             // > 0 where the ray hits the Earth
  float t = b - sqrt(max(disc, 0.0));
  vec2 uv = cropUv(earthLonLat(normalize(rd * t - c)));
  gUvDx = dFdx(uv);
  gUvDy = dFdy(uv);
  float cover = smoothstep(-0.0008, 0.0008, disc);  // ~1 px anti-aliased limb
  // The sky is only computed where it shows.
  vec3 col = cover < 1.0 ? stars(q + parallaxShift(uParallaxStars)) + atmosphereRim(rd, c) : vec3(0.0);
  if (cover > 0.0) col = mix(col, earth(rd, t, c), cover);
  fragColor = vec4(col, 1.0);
}
