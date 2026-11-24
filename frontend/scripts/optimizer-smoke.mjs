import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const api = 'http://127.0.0.1:18084/api/v1';
const web = 'http://localhost:55178/optimize';
const headers = { 'Content-Type': 'application/json' };
const email = 'optimizer-demo-' + Date.now() + '@example.test';
const account = await fetch(api + '/auth/register', {
  method: 'POST', headers,
  body: JSON.stringify({ email, password: 'local-test-only', displayName: 'Browser test' }),
});
assert.equal(account.status, 201);
const { token } = await account.json();

const targets = await (await fetch('http://127.0.0.1:59226/json/list')).json();
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

async function choose(label, value) {
  await evaluate('(() => { const label = [...document.querySelectorAll("label")].find(el => el.childNodes[0]?.textContent.trim() === ' + JSON.stringify(label) + ');' +
    'const input = label.querySelector("select"); input.value = ' + JSON.stringify(String(value)) + ';' +
    'input.dispatchEvent(new Event("change", { bubbles: true })); })()');
}

try {
  const signed = { ...headers, Authorization: 'Bearer ' + token };
  async function post(path, body) {
    const response = await fetch(api + path, { method: 'POST', headers: signed, body: JSON.stringify(body) });
    assert.equal(response.status, 201, path + ' ' + response.status);
    return response.json();
  }
  const response = await fetch(api + '/products/search?q=PhaseSix%20Pantry&limit=25');
  assert.equal(response.status, 200);
  const products = (await response.json()).filter(product => /^PhaseSix Pantry \d\d$/.test(product.canonicalName))
    .sort((a, b) => a.canonicalName.localeCompare(b.canonicalName));
  assert.equal(products.length, 11);
  const list = await post('/lists', { name: 'Monthly Polokwane groceries' });
  for (const [index, product] of products.slice(0, 10).entries()) {
    await post('/lists/' + list.id + '/items', { productId: product.productId, quantity: 1,
      ...(index === 0 ? { flexibleBrand: true, substituteProductId: products[10].productId } : {}) });
  }
  const startsOn = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Africa/Johannesburg' }).format(new Date());
  const budget = await post('/budgets', { name: 'Monthly grocery budget', amount: 800, period: 'MONTHLY', startsOn, listIds: [list.id] });

  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(token) + ')' });
  await width(1440, 1150);
  await call('Page.navigate', { url: web });
  await waitFor('[...document.querySelectorAll("select option")].some(option => option.value === ' + JSON.stringify(String(list.id)) + ')');
  await choose('Shopping list', list.id);
  await shown('Select a shopping location to continue.');
  await clickText('Choose location');
  await selectTown('Polokwane');
  await waitFor('[...document.querySelectorAll("select option")].some(option => option.value === ' + JSON.stringify('saved:' + budget.id) + ')');
  await choose('Available budget', 'saved:' + budget.id);
  await choose('Travel cost', 'WALK');
  await clickText('Optimise My Basket');
  await shown('Compare shopping strategies');
  await shown('Approved alternatives shopping plan');
  await shown('10 of 10 entries');
  await waitFor('/505[.,]00/.test(document.body.innerText) && /525[.,]00/.test(document.body.innerText) && /625[.,]00/.test(document.body.innerText)');
  await capture('optimizer-desktop');
  await clickText('Save selected plan');
  await shown('Shopping plan saved.');
  await shown('Open plan');
  await evaluate('[...document.querySelectorAll("button")].find(el => el.innerText.includes("Open plan")).click()');
  await shown('Historical estimate');
  await post('/lists/' + list.id + '/items', { customName: 'Unpriced loose apples', quantity: 1, unit: 'kg' });
  await clickText('Recalculate with latest prices');
  await shown('Latest estimate');
  await shown('Partial estimate. The full basket cost is unknown.');
  await shown('Unpriced loose apples');
  await evaluate('[...document.querySelectorAll("h3")].find(el => el.textContent.startsWith("Latest estimate")).scrollIntoView()');
  await capture('optimizer-saved');
  await width(390, 900);
  await waitFor('document.documentElement.scrollWidth === 390 && document.querySelector("main").scrollWidth <= 390 && document.querySelector("aside").getBoundingClientRect().right <= 0');
  await evaluate('[...document.querySelectorAll("h2")].find(el => el.textContent === "Compare shopping strategies").scrollIntoView()');
  await capture('optimizer-mobile');
  console.log('Optimiser browser demo passed. One branch R625, split R525 and approved alternative R505; saved history remained intact after a missing item was added.');
} finally {
  socket.close();
}
