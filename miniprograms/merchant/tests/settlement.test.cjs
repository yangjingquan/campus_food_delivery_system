const test = require('node:test');
const assert = require('node:assert/strict');
const api = require('../utils/api');

test('商户结算展示固定抽成、退款后餐品基数和实际入账', async t => {
  let page;
  global.Page = value => { page = value; };
  require('../pages/records/index');
  t.after(() => { delete global.Page; });
  t.mock.method(api, 'request', async () => [{
    id: 'order', foodCents: 2000, foodRefundedCents: 700, foodBaseCents: 1300,
    foodRuleMode: 'FIXED', foodRuleValue: 165, foodCommissionCents: 165, settledCents: 1135
  }]);
  page.kind = 'settlements';
  page.setData = patch => Object.assign(page.data, patch);
  await page.refresh();
  const row = page.data.rows[0];
  assert.equal(row.foodRule, '¥1.65');
  assert.equal(row.foodCommission, '¥1.65');
  assert.equal(row.foodBase, '¥13.00');
  assert.equal(row.net, '¥11.35');
});
