import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const api = 'http://127.0.0.1:18082/api/v1';
const web = 'http://localhost:55176/discover?q=Verification+milk';
const headers = { 'Content-Type': 'application/json' };
const email = 'lists-demo-' + Date.now() + '@example.test';
const account = await fetch(api + '/auth/register', {
  method: 'POST', headers,
  body: JSON.stringify({ email, password: 'local-test-only', displayName: 'Browser test' }),
});
assert.equal(account.status, 201);
const { token } = await account.json();

const targets = await (await fetch('http://127.0.0.1:59224/json/list')).json();
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
  await evaluate('[...document.querySelectorAll("button,a,summary")].find(button => button.innerText.trim() === ' + JSON.stringify(text) + ').click()');
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

async function select(selector, value) {
  await evaluate('(() => { const control = document.querySelector(' + JSON.stringify(selector) + '); ' +
    'control.value = ' + JSON.stringify(value) + '; control.dispatchEvent(new Event("change", { bubbles: true })); })()');
}
async function nav(path) {
  await evaluate('document.querySelector(\'nav a[href="' + path + '"]\').click()');
}
const milkAdd = 'button[aria-label="Add Verification Milk 2 L to shopping list"]';
const detail = 'section[aria-label="Open shopping list"]';
function shown(text) {
  return waitFor('document.body.innerText.includes(' + JSON.stringify(text) + ')');
}

try {
  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(token) + ')' });
  await width(1440, 1000);
  await call('Page.navigate', { url: web });
  await waitFor('Boolean(document.querySelector(' + JSON.stringify(milkAdd) + '))');
  await evaluate('document.querySelector(' + JSON.stringify(milkAdd) + ').click()');
  await shown('New list name');
  await enter('dialog input', 'Weekly groceries');
  await clickText('Add product');
  await shown('Verification Milk 2 L added to your shopping list.');
  await nav('/lists');
  await shown('Weekly groceries');
  await clickText('Create list');
  await enter('form input[name=name]', 'Household');
  await clickText('Create list');
  await shown('Household');

  await nav('/discover');
  await enter('input[name=q]', 'Verification milk');
  await evaluate('document.querySelector(\'form[aria-label="Catalogue search"] button[type=submit]\').click()');
  await waitFor('Boolean(document.querySelector(' + JSON.stringify(milkAdd) + '))');
  await evaluate('document.querySelector(' + JSON.stringify(milkAdd) + ').click()');
  await shown('Add product to a list');
  await evaluate('(() => { const list = document.querySelector("dialog select"); list.value = [...list.options].find(option => option.text === "Weekly groceries").value; list.dispatchEvent(new Event("change", {bubbles:true})); })()');
  await clickText('Add product');
  await shown('Verification Milk 2 L added to your shopping list.');
  await nav('/lists');
  await waitFor('Boolean(document.querySelector(' + JSON.stringify(detail) + '))');
  await evaluate('[...document.querySelectorAll("li")].find(li => li.querySelector("h2")?.textContent === "Weekly groceries").querySelector("button").click()');
  await shown('4 L total');
  await clickText('Add custom item');
  await enter('input[name=customName]', 'Loose apples');
  await select('select[name=unit]', 'kg');
  await enter('input[name=quantity]', '1.25');
  await enter('input[name=notes]', 'For lunches');
  await evaluate('document.querySelector("input[name=customName]").form.querySelector("button[type=submit]").click()');
  await shown('For lunches');
  await evaluate('[...document.querySelectorAll("li label")].find(label => label.textContent.trim() === "Verification Milk 2 L").querySelector("input").click()');
  await shown('1 of 2 purchased');
  const signedIn = await fetch(api + '/auth/login', {
    method: 'POST', headers, body: JSON.stringify({ email, password: 'local-test-only' }),
  });
  assert.equal(signedIn.status, 200);
  const freshToken = (await signedIn.json()).token;
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(freshToken) + ')' });
  await call('Page.reload');
  await shown('1 of 2 purchased');
  await shown('For lunches');
  assert.match(await evaluate('document.querySelector(' + JSON.stringify(detail) + ').innerText'), /Price unavailable/);
  console.log('Add from Discover with no list, destination choice, quantity merge, custom item, and purchased state after refresh passed.');

  await clickText('Choose a location to estimate prices');
  await shown('Shopping location');
  await selectTown('Polokwane');
  await clickText('Continue with this location');
  await shown('Lowest-price subtotal across branches for 1 of 2 entries: R48.00');
  await shown('1 entry lacks a usable price. The full cost is unknown.');
  await evaluate('document.querySelector(' + JSON.stringify(detail) + ').scrollIntoView()');
  await capture('shopping-lists-desktop');

  await clickText('Reuse or complete this list');
  const copyName = 'details[open] input';
  await enter(copyName, 'Next week');
  await clickText('Create reusable copy');
  await shown('Next week');
  await shown('0 of 2 purchased');
  assert.match(await evaluate('document.querySelector(' + JSON.stringify(detail) + ').innerText'), /For lunches/);
  await width(390, 900);
  await waitFor('document.querySelector("aside").getBoundingClientRect().right <= 0');
  assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'));
  await evaluate('document.querySelector(' + JSON.stringify(detail) + ').scrollIntoView()');
  await capture('shopping-lists-mobile');
  console.log('Location-aware partial estimate, independent copy with reset purchases, and phone layout passed.');
  const authorized = { ...headers, Authorization: 'Bearer ' + freshToken };
  const lists = await (await fetch(api + '/lists', { headers: authorized })).json();
  const copy = lists.find(list => list.name === 'Next week');
  const custom = copy.items.find(item => item.customName === 'Loose apples');
  const editUrl = api + '/lists/' + copy.id + '/items/' + custom.id + '?revision=' + copy.revision;
  const edits = await Promise.all(['First edit', 'Second edit'].map(notes => fetch(editUrl, {
    method: 'PUT', headers: authorized, body: JSON.stringify({ customName: custom.customName,
      quantity: custom.quantity, unit: custom.unit, notes }),
  })));
  assert.deepEqual(edits.map(response => response.status).sort(), [200, 409]);
  const milk = copy.items.find(item => item.productId);
  const key = crypto.randomUUID();
  const adds = await Promise.all([0, 1].map(() => fetch(api + '/lists/' + copy.id + '/items', {
    method: 'POST', headers: { ...authorized, 'Idempotency-Key': key },
    body: JSON.stringify({ productId: milk.productId, quantity: 1 }),
  })));
  assert.deepEqual(adds.map(response => response.status), [201, 201]);
  assert.deepEqual(await Promise.all(adds.map(async response => (await response.json()).quantity)), [3, 3]);
  console.log('Simultaneous stale edits and overlapping add retries passed against committed PostgreSQL data.');

} finally {
  socket.close();
}
