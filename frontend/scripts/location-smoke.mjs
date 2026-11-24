import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const api = 'http://127.0.0.1:18081/api/v1';
const web = 'http://localhost:55175/stores';
const headers = { 'Content-Type': 'application/json' };
const account = await fetch(api + '/auth/register', {
  method: 'POST', headers,
  body: JSON.stringify({ email: 'location-demo-' + Date.now() + '@example.test', password: 'local-test-only', displayName: 'Browser test' }),
});
assert.equal(account.status, 201);
const { token } = await account.json();

const targets = await (await fetch('http://127.0.0.1:59223/json/list')).json();
const socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
let sequence = 0;
const pending = new Map();
socket.onmessage = event => {
  const message = JSON.parse(event.data);
  if (!pending.has(message.id)) return;
  const { resolve, reject } = pending.get(message.id);
  pending.delete(message.id);
  if (message.error) reject(new Error(message.error.message));
  else resolve(message.result);
};
function call(method, params = {}) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text + ': ' + expression);
  return result.result.value;
}
async function waitFor(expression) {
  for (let tries = 0; tries < 80; tries++) {
    try { if (await evaluate(expression)) return; } catch {}
    await delay(250);
  }
  throw new Error('Timed out waiting for ' + expression + '\n' + await evaluate('document.body?.innerText'));
}
async function width(width, height) {
  await call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
}


async function clickText(text) {
  await evaluate('[...document.querySelectorAll("button,a")].find(button => button.innerText.trim().includes(' + JSON.stringify(text) + ')).click()');
}
async function enter(selector, value) {
  await evaluate('(() => { const input = document.querySelector(' + JSON.stringify(selector) + '); ' +
    'Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, ' + JSON.stringify(value) + '); ' +
    'input.dispatchEvent(new Event("input", { bubbles: true })); })()');
}
async function selectTown(name) {
  await enter('section[aria-label="Shopping location"] form input', name);
  await clickText('Search locations');
  await waitFor('Boolean(document.querySelector("input[name=locality]"))');
  await evaluate('document.querySelector("input[name=locality]").click()');
  await clickText('Confirm location');
}
async function capture(name) {
  const { data } = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  await writeFile('backend/target/' + name + '.png', Buffer.from(data, 'base64'));
}
const mockLocation = function () {
  let gpsCalls = 0;
  window.getGpsCalls = () => gpsCalls;
  window.gpsMode = 'denied';
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    value: { query: async () => ({ state: 'prompt', addEventListener() {}, removeEventListener() {} }) },
  });
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition(success, failure) {
      gpsCalls++;
      if (window.gpsMode === 'denied') failure({ code: 1 });
      else if (window.gpsMode === 'timeout') failure({ code: 3 });
      else success({ coords: { latitude: -34.2313647, longitude: 19.4252453,
        accuracy: window.gpsMode === 'poor' ? 3200 : 30 } });
    } },
  });
};

try {
  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") { localStorage.setItem("token", ' + JSON.stringify(token) + '); (' + mockLocation.toString() + ')(); }' });
  await width(1440, 1000);
  await call('Page.navigate', { url: web });
  await waitFor('document.body.innerText.includes("Choose a location to find nearby branches")');
  assert.equal(await evaluate('window.getGpsCalls()'), 0);
  await clickText('Use my location');
  await waitFor('document.body.innerText.includes("Location permission was denied")');
  assert.equal(await evaluate('[...document.querySelectorAll("button")].find(b => b.innerText.includes("Use my location")).disabled'), true);
  assert.equal(await evaluate('window.getGpsCalls()'), 1);
  await selectTown('Polokwane');
  await waitFor('document.body.innerText.includes("Food Lover?s Market The Farmyard") || document.body.innerText.includes("Food Lover\'s Market The Farmyard")');
  assert.match(await evaluate('document.body.innerText'), /Polokwane, Limpopo/);
  assert.match(await evaluate('document.body.innerText'), /km straight line/);
  await capture('location-discovery-desktop');
  await clickText('Show map');
  await waitFor('document.body.innerText.includes("Map tiles could not load")');
  assert.match(await evaluate('document.body.innerText'), /View branch and prices/);
  await clickText('View branch and prices');
  await waitFor('document.body.innerText.includes("Branch information source")');
  assert.match(await evaluate('document.body.innerText'), /Buzworx Avenue/);
  assert.match(await evaluate('document.body.innerText'), /015 590 5000/);
  await clickText('Show observation history');
  await waitFor('document.body.innerText.includes("Recorded observations")');
  await width(390, 900);
  await waitFor('document.querySelector("aside").getBoundingClientRect().right <= 0');
  assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'));
  await capture('location-branch-mobile');
  console.log('Denied GPS, manual Polokwane, genuine branch details, failed map tiles and phone viewport passed.');

  await call('Page.navigate', { url: web });
  await waitFor('document.body.innerText.includes("Choose a location to find nearby branches")');
  await width(768, 950);
  await evaluate('window.gpsMode = "timeout"');
  await clickText('Use my location');
  await waitFor('document.body.innerText.includes("GPS timed out")');
  await evaluate('window.gpsMode = "poor"');
  await clickText('Use my location');
  await waitFor('document.body.innerText.includes("too imprecise")');
  await selectTown('Bergville');
  await waitFor('document.body.innerText.includes("Bergville, KwaZulu-Natal")');
  await waitFor('document.body.innerText.includes("No registered branches match")');
  await evaluate('window.gpsMode = "good"');
  await clickText('Use my location');
  await waitFor('document.body.innerText.includes("FreshStop Caledon")');
  assert.match(await evaluate('document.body.innerText'), /GPS location/);
  assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'));
  await evaluate('document.querySelector("a[href=\\"/discover\\"]").click()');
  await waitFor('document.body.innerText.includes("Prices shown within")');
  await evaluate('document.querySelector("a[href=\\"/stores\\"]").click()');
  await waitFor('document.body.innerText.includes("FreshStop Caledon")');
  await clickText('Clear location');
  await waitFor('document.body.innerText.includes("Choose a location to find nearby branches")');
  assert.equal(await evaluate('localStorage.getItem("shoppingDiary.location")'), null);
  await evaluate('Object.defineProperty(navigator, "geolocation", { value: undefined, configurable: true })');
  await clickText('Use my location');
  await waitFor('document.body.innerText.includes("This browser cannot provide GPS")');
  console.log('Timeout, poor accuracy, unsupported GPS, smaller-town empty state, manual-to-GPS switch, shared location, and privacy checks passed at tablet width.');
} finally {
  socket.close();
}
