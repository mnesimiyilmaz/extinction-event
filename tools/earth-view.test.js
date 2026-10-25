import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { latLonAt, visibleBounds } from './earth-view.js';

const tuning = JSON.parse(readFileSync(new URL('../app/src/main/assets/config/tuning.json', import.meta.url), 'utf8'));

test('the impact point maps exactly to impactLatLon', () => {
  const [lat, lon] = latLonAt(tuning, tuning.impactQ);
  assert.ok(Math.abs(lat - tuning.impactLatLon[0]) < 1e-9, `lat ${lat}`);
  assert.ok(Math.abs(lon - tuning.impactLatLon[1]) < 1e-9, `lon ${lon}`);
});

test('at heading 0, up-screen is north and right is east', () => {
  const t = { ...tuning, northHeading: 0 };
  const [qx, qy] = t.impactQ;
  const [lat0, lon0] = latLonAt(t, [qx, qy]);
  assert.ok(latLonAt(t, [qx, qy + 0.02])[0] > lat0, 'up-screen should increase latitude');
  assert.ok(latLonAt(t, [qx, qy - 0.02])[0] < lat0, 'down-screen should decrease latitude');
  assert.ok(latLonAt(t, [qx + 0.02, qy])[1] > lon0, 'right should increase longitude');
});

test('rays that miss the Earth have no coordinates', () => {
  assert.equal(latLonAt(tuning, [0.23, 0.5]), null); // top-right corner is space
});

test('earthCrop in tuning.json covers the visible surface, camera sway and cloud flow', () => {
  const b = visibleBounds(tuning);
  const [lon0, lon1, lat0, lat1] = tuning.earthCrop;
  const m = (tuning.cloudWind * tuning.cloudCycle) / 2;
  assert.ok(lon0 <= b.lon0 - m && lon1 >= b.lon1 + m, `lon crop ${lon0}..${lon1} vs visible ${b.lon0}..${b.lon1}`);
  assert.ok(lat0 <= b.lat0 && lat1 >= b.lat1, `lat crop ${lat0}..${lat1} vs visible ${b.lat0}..${b.lat1}`);
});
