const test = require('node:test');
const assert = require('node:assert/strict');
const display = require('../utils/display');

test('正常单按实际配送费、平台抽成和骑手应得展示', () => {
  const order = display.order({ feeCents: 251, commissionCents: 199, dueCents: 52, fundedCents: 0 });
  assert.equal(order.fee, '2.51');
  assert.equal(order.commission, '1.99');
  assert.equal(order.due, '0.52');
  assert.equal(order.funded, '0.00');
});

test('全额退款的骑手应得与原配送费及平台抽成独立展示', () => {
  const amounts = { feeCents: 0, commissionCents: 0, dueCents: 250, fundedCents: 250 };
  for (const row of [display.order(amounts), display.finance(amounts)]) {
    assert.equal(row.fee, '0.00');
    assert.equal(row.commission, '0.00');
    assert.equal(row.due, '2.50');
    assert.equal(row.funded, '2.50');
  }
});
