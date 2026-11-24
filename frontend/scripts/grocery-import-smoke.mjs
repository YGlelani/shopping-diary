import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { writeFile } from 'node:fs/promises';

const api = 'http://127.0.0.1:18086/api/v1';
const web = 'http://localhost:55180';
const source = '/admin/grocery-automation';
const headers = { 'Content-Type': 'application/json' };
const login = await fetch(api + '/auth/login', { method: 'POST', headers, body: JSON.stringify({
  email: 'automation-demo-admin@example.test', password: 'local-test-only',
}) });
assert.equal(login.status, 200);
const { token } = await login.json();
const signed = { ...headers, Authorization: 'Bearer ' + token };

async function request(path, method = 'GET', body, expected = 200) {
  const response = await fetch(api + path, { method, headers: signed, body: body && JSON.stringify(body) });
  const raw = await response.text();
  assert.equal(response.status, expected, path + ' ' + raw);
  return raw ? JSON.parse(raw) : null;
}

let status;
for (let attempt = 0; attempt < 110; attempt++) {
  status = await request(source);
  if (['OPEN_FOOD_FACTS', 'OPEN_STREET_MAP', 'OPEN_PRICES'].every(name => status.runs.some(run => run.source === name && run.status === 'SUCCEEDED'))) break;
  await delay(1000);
}
const initial = ['OPEN_FOOD_FACTS', 'OPEN_STREET_MAP', 'OPEN_PRICES'].map(name =>
  status.runs.find(run => run.source === name && run.status === 'SUCCEEDED' && run.imported > 0));
assert.ok(initial.every(Boolean), JSON.stringify(status.runs));
assert.ok(initial.every(run => Date.parse(run.finishedAt) >= Date.parse(run.startedAt)));
const repeated = [];
for (const name of ['OPEN_FOOD_FACTS', 'OPEN_STREET_MAP', 'OPEN_PRICES']) {
  const replay = await request(source + '/' + name + '/sync?dryRun=false', 'POST');
  assert.equal(replay.imported, 0);
  repeated.push(replay);
}
const preview = await request(source + '/OPEN_FOOD_FACTS/sync?dryRun=true', 'POST');
assert.equal(preview.dryRun, true);
await request(source + '/LOYALTYHUB/sync?query=milk&dryRun=false', 'POST', undefined, 503);
const failure = (await request(source)).runs.find(run => run.source === 'LOYALTYHUB' && run.status === 'FAILED');
assert.ok(failure);
const [product] = await request('/products/search?q=6009802437896');
assert.equal(product.canonicalName, 'Udon Noodles');
const evidence = await request('/scoped-prices/' + product.productId);
assert.equal(evidence[0].source, 'OPEN_PRICES');
assert.equal(evidence[0].observedOn, '2026-10-13');
assert.equal(evidence[0].amount, 44.99);
assert.equal(evidence[0].historical, true);
const list = await request('/lists', 'POST', { name: 'Genuine Udon Noodles basket' }, 201);
await request('/lists/' + list.id + '/items', 'POST', { productId: product.productId, quantity: 1 }, 201);
const quote = await request('/lists/' + list.id + '/optimize', 'POST', { lat: -29.8488493, lon: 30.9986822, travelMode: 'WALK' });
assert.equal(quote.recommendation.budgetStatus, 'NO_FEASIBLE_PLAN');
assert.equal(quote.recommendation.missingItems, 1);

const targets = await (await fetch('http://127.0.0.1:59228/json/list')).json();
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
    try { if (await evaluate(expression)) return; } catch { /* wait for navigation */ }
    await delay(250);
  }
  throw new Error('Timed out waiting for ' + expression + '\n' + await evaluate('document.body?.innerText'));
}
async function capture(name) {
  const { data } = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  await writeFile('backend/target/' + name + '.png', Buffer.from(data, 'base64'));
}

try {
  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(token) + ')' });
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1050, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: web + '/admin' });
  await waitFor('document.body.innerText.includes("Free grocery imports") && document.body.innerText.includes("Open Prices exports")');
  await evaluate('[...document.querySelectorAll("h2")].find(el => el.textContent === "Free grocery imports").scrollIntoView()');
  await evaluate('[...document.querySelectorAll("summary")].find(el => el.textContent === "Recent import runs").click()');
  await capture('grocery-import-admin');
  await call('Page.navigate', { url: web + '/discover?q=6009802437896' });
  const button = '[aria-label="View details for Udon Noodles"]';
  await waitFor('Boolean(document.querySelector(' + JSON.stringify(button) + '))');
  await evaluate('document.querySelector(' + JSON.stringify(button) + ').click()');
  await waitFor('document.body.innerText.includes("Open Prices observation") && document.body.innerText.includes("R44.99")');
  await evaluate('[...document.querySelectorAll("h3")].find(el => el.textContent === "Other price observations").scrollIntoView()');
  await capture('grocery-import-observation');
  const report = { initial, repeated, preview, disabledProvider: failure,
    exactObservation: evidence[0], basketStatus: quote.recommendation.budgetStatus };
  await writeFile('backend/target/grocery-import-report.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  socket.close();
}
