import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const api = 'http://127.0.0.1:18085/api/v1';
const web = 'http://localhost:55179/specials';
const headers = { 'Content-Type': 'application/json' };
const email = 'specials-demo-' + Date.now() + '@example.test';
const account = await fetch(api + '/auth/register', {
  method: 'POST', headers,
  body: JSON.stringify({ email, password: 'local-test-only', displayName: 'Browser test' }),
});
assert.equal(account.status, 201);
const { token } = await account.json();

const targets = await (await fetch('http://127.0.0.1:59227/json/list')).json();
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

async function openDeal(text) {
  await evaluate('[...document.querySelectorAll("article")].find(el => el.innerText.includes(' + JSON.stringify(text) + ')).querySelector("button").click()');
  await shown('Offer conditions');
}

async function addProduct() {
  await clickText('Add product to shopping list');
  await shown('Add product to a list');
  await clickText('Add product');
  await waitFor('!document.querySelector("dialog[open]")');
}

try {
  const signed = { ...headers, Authorization: 'Bearer ' + token };
  async function post(path, body) {
    const response = await fetch(api + path, { method: 'POST', headers: signed, body: JSON.stringify(body) });
    assert.equal(response.status, 201, path + ' ' + response.status);
    return response.json();
  }
  const list = await post('/lists', { name: 'Specials demonstration list' });
  const startsOn = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Africa/Johannesburg' }).format(new Date());
  const budget = await post('/budgets', { name: 'Fixture grocery budget', amount: 45, period: 'TRIP', startsOn, listIds: [list.id] });
  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source:
    'if (location.hostname === "localhost") localStorage.setItem("token", ' + JSON.stringify(token) + ')' });
  await width(1440, 1100);
  await call('Page.navigate', { url: web });
  await shown('Specials & Deals');
  await clickText('Choose shopping location');
  await selectTown('Polokwane');
  await shown('Nearby specials');
  await enter('input[name=q]', 'Fixture Full Cream Milk 2 L');
  await waitFor('Boolean(document.querySelector("select[name=retailer] option[value=flm]"))');
  await choose('Retailer', 'flm');
  await waitFor('[...document.querySelectorAll("select[name=category] option")].some(option => option.textContent === "Dairy and eggs")');
  await choose('Category', 'Dairy and eggs');
  await clickText('Apply filters');
  await shown('Fixture Full Cream Milk 2 L');
  await openDeal('Fixture loyalty card required');
  await shown('Confirm that you have the required loyalty card.');
  await clickText('Close conditions');
  await openDeal('Buy 2 for');
  await shown('Your quantity is below the offer requirement.');
  await addProduct();
  await clickText('Close conditions');
  await choose('Shopping list', list.id);
  await shown('Specials for your shopping list');
  await shown('List quantity 1');
  await openDeal('Buy 2 for');
  await addProduct();
  await clickText('Close conditions');
  await shown('List quantity 2');
  await shown('This quantity qualifies at the selected branch.');
  await openDeal('Buy 2 for');
  await shown('Verified shelf-price saving');
  await capture('specials-desktop');

  await width(390, 900);
  await waitFor('document.querySelector("aside").getBoundingClientRect().right <= 0 && document.documentElement.scrollWidth <= innerWidth');
  await evaluate('document.querySelector(' + JSON.stringify('section[aria-label="Offer conditions"]') + ').scrollIntoView()');
  await capture('specials-mobile');
  await width(1440, 1100);

  await evaluate('document.querySelector(\'nav a[href="/budgets"]\').click()');
  await shown('Fixture grocery budget');
  await clickText('Fixture grocery budget');
  await shown('Known estimated subtotal');
  await waitFor('/42[.,]00/.test(document.body.innerText)');
  await evaluate('document.querySelector(\'nav a[href="/optimize"]\').click()');
  await shown('Optimise My Basket');
  await choose('Shopping list', list.id);
  await waitFor('[...document.querySelectorAll("select option")].some(option => option.value === ' + JSON.stringify('saved:' + budget.id) + ')');
  await choose('Available budget', 'saved:' + budget.id);
  await choose('Travel cost', 'WALK');
  await clickText('Optimise My Basket');
  await shown('Complete estimate within budget.');
  await clickText('Compare with shelf prices only');
  await shown('Shelf-price comparison');
  await shown('The estimated trip exceeds the budget.');
  await waitFor('/42[.,]00/.test(document.body.innerText) && /48[.,]00/.test(document.body.innerText)');
  await evaluate('[...document.querySelectorAll("h3")].find(el => el.textContent === "Shelf-price comparison").scrollIntoView()');
  await capture('specials-savings-comparison');
  console.log('Specials browser demo passed. One carton and unconfirmed loyalty did not qualify, two cartons cost R42 versus R48 shelf and fit the R45 budget.');
} finally {
  socket.close();
}
