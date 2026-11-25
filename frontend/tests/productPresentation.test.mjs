import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';

let server;
let ProductImage;
let CataloguePrice;

before(async () => {
  server = await createServer({ server: { middlewareMode: true, ws: false } });
  ({ ProductImage, CataloguePrice } = await server.ssrLoadModule('/src/components/ProductPresentation.jsx'));
});

after(async () => {
  await server?.close();
});

test('renders the product image with lazy loading and accessible text', () => {
  const html = renderToStaticMarkup(createElement(ProductImage, {
    product: { canonicalName: 'Maize Meal', imageUrl: 'https://retailer.example/maize.jpg' },
  }));
  assert.match(html, /<img[^>]+src="https:\/\/retailer\.example\/maize\.jpg"/);
  assert.match(html, /alt="Maize Meal"/);
  assert.match(html, /loading="lazy"/);
});

test('renders a neutral fallback when an image is unavailable', () => {
  const html = renderToStaticMarkup(createElement(ProductImage, {
    product: { canonicalName: 'Maize Meal' },
  }));
  assert.match(html, /Image unavailable/);
  assert.doesNotMatch(html, /<img/);
});

test('labels the offer as indicative and includes provider attribution', () => {
  const html = renderToStaticMarkup(createElement(CataloguePrice, {
    product: { catalogueAmount: 79.99, catalogueRetailer: 'Checkers' },
  }));
  assert.match(html, /79,99|79\.99/);
  assert.match(html, /Checkers \(indicative catalogue price\)/);
  assert.match(html, /href="https:\/\/loyaltyhub\.co\.za\/">Prices via Loyalty Hub/);
  assert.equal(renderToStaticMarkup(createElement(CataloguePrice, { product: {} })), '');
});
