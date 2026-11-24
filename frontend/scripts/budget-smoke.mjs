import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const api = 'http://127.0.0.1:18083/api/v1';
const web = 'http://localhost:55177/budgets';
const headers = { 'Content-Type': 'application/json' };
const email = 'budgets-demo-' + Date.now() + '@example.test';
const account = await fetch(api + '/auth/register', {
  method: 'POST', headers,
  body: JSON.stringify({ email, password: 'local-test-only', displayName: 'Browser test' }),
});
assert.equal(account.status, 201);
const { token } = await account.json();

const targets = await (await fetch('http://127.0.0.1:59225/json/list')).json();
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

function shown(text) {
  return waitFor('document.body.innerText.includes(' + JSON.stringify(text) + ')');
}

try {
  const signed = { ...headers, Authorization: 'Bearer ' + token };
  const productResponse = await fetch(api + '/products/search?q=Verification+Milk+2+L');
  assert.equal(productResponse.status, 200);
  const [product] = await productResponse.json();
  assert.equal(product.canonicalName, 'Verification Milk 2 L');
  const listResponse = await fetch(api + '/lists', { method: 'POST', headers: signed, body: JSON.stringify({ name: 'Weekly basket' }) });
  assert.equal(listResponse.status, 201);
  const list = await listResponse.json();
  for (const item of [{ productId: product.productId, quantity: 3 }, { customName: 'Loose apples', quantity: 1, unit: 'kg' }]) {
    const add = await fetch(api + '/lists/' + list.id + '/items', { method: 'POST', headers: signed, body: JSON.stringify(item) });
    assert.equal(add.status, 201);
  }

  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(token) + ')' });
  await width(1440, 1120);
  await call('Page.navigate', { url: web + '?listId=' + list.id });
  await shown('Weekly basket selected');
  await clickText('New budget');
  await enter('input[name=amount]', '500');
  await clickText('Save budget');
  await shown('Weekly basket budget');
  await shown('No eligible prices are available');
  await clickText('Choose a shopping location');
  await shown('Nearby stores');
  await selectTown('Polokwane');
  await shown('Continue with this location');
  await clickText('Continue with this location');
  await shown('Partial estimate. Affordability is unknown');
  await shown('1 of 2 entries');
  await shown('Price unavailable');
  await clickText('Record purchase');
  await enter('input[name=description]', 'Milk receipt');
  await enter('input[name=amount]', '73.45');
  await enter('input[name=category]', 'Dairy');
  await clickText('Save purchase');
  await shown('Milk receipt');
  await waitFor('/426[.,]55/.test(document.body.innerText)');
  const login = await fetch(api + '/auth/login', {
    method: 'POST', headers, body: JSON.stringify({ email, password: 'local-test-only' }),
  });
  assert.equal(login.status, 200);
  const renewed = await login.json();
  const id = new URL(await evaluate('location.href')).searchParams.get('id');
  const saved = await fetch(api + '/budgets/' + id, { headers: { Authorization: 'Bearer ' + renewed.token } });
  assert.equal(saved.status, 200);
  assert.equal((await saved.json()).actualRemaining, 426.55);
  await call('Page.reload');
  await shown('Milk receipt');
  await shown('No eligible prices are available');
  await clickText('Choose a shopping location');
  await shown('Nearby stores');
  await selectTown('Polokwane');
  await shown('Continue with this location');
  await clickText('Continue with this location');
  await shown('Partial estimate. Affordability is unknown');
  await capture('budget-planning-desktop');

  await clickText('Correct');
  await enter('input[name=amount]', '74.10');
  await clickText('Save purchase');
  await waitFor('/425[.,]90/.test(document.body.innerText)');
  await width(390, 900);
  await waitFor('document.documentElement.scrollWidth === 390 && document.querySelector("main").scrollWidth <= 390');
  await capture('budget-planning-mobile');
  await evaluate('[...document.querySelectorAll("h2")].find(el => el.textContent === "Actual purchases").scrollIntoView()');
  await capture('budget-purchases-mobile');

  await clickText('Reuse');
  await enter('input[name=name]', 'Next weekly budget');
  await clickText('Save budget');
  await shown('Next weekly budget');
  await shown('No purchases recorded for this period.');
  assert.equal(await evaluate('document.body.innerText.includes("Milk receipt")'), false);
  console.log('Budget browser workflow passed on desktop and mobile.');
} finally {
  socket.close();
}
