// Screen → geographic mapping for the Earth texture. Mirrors common.glsl (camera, raySphere,
// impactNormal) and background.frag (earthLonLat); keep the three in sync.

export const ASPECT = 9 / 19.5; // phone width / height

const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const scale = (a, s) => a.map((x) => x * s);
const add = (a, b) => a.map((x, i) => x + b[i]);
const sub = (a, b) => a.map((x, i) => x - b[i]);
const norm = (a) => scale(a, 1 / Math.hypot(...a));
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];

const focal = (t) => 0.5 / Math.tan(rad(t.camFovY) / 2);
const earthCenter = (t) => scale(norm([t.earthDir[0], t.earthDir[1], -focal(t)]), t.earthDist);

// Camera-space unit normal of the Earth's surface seen at screen position q, or null on a miss.
function surfaceNormal(t, q) {
  const rd = norm([q[0], q[1], -focal(t)]);
  const c = earthCenter(t);
  const b = dot(rd, c);
  const h = b * b - dot(c, c) + 1;
  if (h < 0) return null;
  return norm(sub(scale(rd, b - Math.sqrt(h)), c));
}

// Tangent frame at the impact point in camera space (E, N, nI) and in Earth-fixed space
// (east, north, e0). Earth-fixed: x through lon 0, z through the north pole.
function frames(t) {
  const nI = surfaceNormal(t, t.impactQ);
  let n = norm(sub([0, 1, 0], scale(nI, nI[1])));
  const th = rad(t.northHeading);
  n = add(scale(n, Math.cos(th)), scale(cross(nI, n), Math.sin(th)));
  const [la, lo] = t.impactLatLon.map(rad);
  return {
    E: cross(n, nI),
    N: n,
    nI,
    east: [-Math.sin(lo), Math.cos(lo), 0],
    north: [-Math.sin(la) * Math.cos(lo), -Math.sin(la) * Math.sin(lo), Math.cos(la)],
    e0: [Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la)],
  };
}

function toLatLon(f, n) {
  const v = add(add(scale(f.east, dot(n, f.E)), scale(f.north, dot(n, f.N))), scale(f.e0, dot(n, f.nI)));
  return [deg(Math.asin(Math.max(-1, Math.min(1, v[2])))), deg(Math.atan2(v[1], v[0]))];
}

// [lat, lon] in degrees of the surface at screen position q (no parallax), or null in space.
export function latLonAt(t, q) {
  const n = surfaceNormal(t, q);
  return n && toLatLon(frames(t), n);
}

// Lat/lon box of every surface point the screen can show, across the full parallax range plus
// camera sway and impact shake (common.glsl parallaxShift: offset ±0.5, sway ±1/±0.6, shake ±1).
export function visibleBounds(t, steps = 96) {
  const f = frames(t);
  const b = { lon0: Infinity, lon1: -Infinity, lat0: Infinity, lat1: -Infinity };
  const shifts = [];
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) {
    shifts.push([sx * (0.5 + t.sway + t.shake) * t.parallaxEarth, sy * (0.6 * t.sway + t.shake) * t.parallaxEarth]);
  }
  for (const [shx, shy] of shifts) {
    for (let i = 0; i <= steps; i++) {
      for (let j = 0; j <= steps * 2; j++) {
        const q = [(i / steps - 0.5) * ASPECT + shx, j / (steps * 2) - 0.5 + shy];
        const n = surfaceNormal(t, q);
        if (!n) continue;
        const [lat, lon] = toLatLon(f, n);
        b.lon0 = Math.min(b.lon0, lon);
        b.lon1 = Math.max(b.lon1, lon);
        b.lat0 = Math.min(b.lat0, lat);
        b.lat1 = Math.max(b.lat1, lat);
      }
    }
  }
  return b;
}
