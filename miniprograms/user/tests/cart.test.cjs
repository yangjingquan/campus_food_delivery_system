const test = require('node:test');
const assert = require('node:assert/strict');
global.wx = {
  getStorageSync(key) { return this.values?.[key] || []; },
  setStorageSync(key, value) { this.values = { ...(this.values || {}), [key]: value }; }
};
const cart = require('../utils/cart');

test('single merchant checkout charges one configured delivery fee', () => {
  const result = cart.totals([{ merchantId: 'M03', priceCents: 2000, quantity: 1 }], 400);
  assert.deepEqual(result, { foodCents: 2000, deliveryFeeCents: 400, totalCents: 2400, merchantCount: 1 });
});

test('multiple merchants share payment and pay one fee per merchant', () => {
  const items = [
    { merchantId: 'M03', priceCents: 2000, quantity: 1 },
    { merchantId: 'M04', priceCents: 1600, quantity: 1 }
  ];
  assert.deepEqual(cart.totals(items, 400), { foodCents: 3600, deliveryFeeCents: 800, totalCents: 4400, merchantCount: 2 });
  assert.equal(cart.groups(items).length, 2);
});

test('quantity changes do not add another fee for the same merchant', () => {
  const result = cart.totals([{ merchantId: 'M03', priceCents: 2000, quantity: 2 }], 400);
  assert.equal(result.totalCents, 4400);
  assert.equal(result.deliveryFeeCents, 400);
});

test('adding the same product merges quantity and respects available stock', () => {
  const merchant = { id: 'M03', name: '巷口小碗饭' };
  const product = { id: 'P1', name: '香煎鸡腿饭', priceCents: 2000, availableStock: 2 };
  cart.save([]);
  cart.add(cart.read(), product, merchant, 1);
  const result = cart.add(cart.read(), product, merchant, 2);
  assert.equal(result.length, 1);
  assert.equal(result[0].quantity, 2);
});
