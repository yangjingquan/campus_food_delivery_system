const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../pages/orders/index.js'), 'utf8');

function workbench(fetchOrders) {
  let definition;
  const calls = [];
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    require(name) {
      if (name.endsWith('/api')) {
        return { request(url, params) {
          assert.equal(url, '/orders');
          const query = { ...params };
          calls.push(query);
          return fetchOrders(query);
        } };
      }
      return { access: async () => ({ blocked: '' }), money: String, date: String };
    }
  });
  const page = {
    ...definition, data: { ...definition.data },
    setData(values) { Object.assign(this.data, values); }
  };
  return { page, calls };
}

const orders = (ids, hasMore) => ({
  rows: ids.map(id => ({ id, paidCents: 100 })), hasMore, counts: {}
});
const idsOf = page => Array.from(page.data.rows, order => order.id);

test('触底追加下一页，沿用已提交关键词且重复触底只发起一次请求', async () => {
  let finish;
  const first = Array.from({ length: 10 }, (_, index) => 'order-' + index);
  const { page, calls } = workbench(({ page }) => page === 0
    ? orders(first, true)
    : new Promise(resolve => { finish = resolve; }));
  page.search({ detail: { value: '#W' } });
  await page.submitSearch();
  page.search({ detail: { value: '#Z' } });
  const loading = page.onReachBottom();
  page.onReachBottom();
  assert.deepEqual(calls, [
    { status: '', q: '#W', page: 0 }, { status: '', q: '#W', page: 1 }
  ]);
  finish(orders(['order-9', 'order-10'], false));
  await loading;
  assert.deepEqual(idsOf(page), [...first, 'order-10']);
  await page.onReachBottom();
  assert.equal(calls.length, 2);
});

test('续页失败保留列表，重试相同页码', async () => {
  let failed = false;
  const { page, calls } = workbench(({ page }) => {
    if (page === 0) return orders(['first'], true);
    if (!failed) {
      failed = true;
      throw new Error('网络错误');
    }
    return orders(['second'], false);
  });
  await page.refresh();
  await page.onReachBottom();
  assert.equal(page.data.moreError, '网络错误');
  assert.deepEqual(idsOf(page), ['first']);
  await page.onReachBottom();
  assert.equal(calls.length, 2);
  await page.loadMore();
  assert.deepEqual(calls.map(query => query.page), [0, 1, 1]);
  assert.deepEqual(idsOf(page), ['first', 'second']);
});

test('切换状态从第 0 页查询，忽略旧筛选的迟到续页', async () => {
  let finish;
  const { page, calls } = workbench(({ status, page }) => {
    if (status === 'AWAITING_MERCHANT') return orders(['waiting'], false);
    if (page === 0) return orders(['first'], true);
    return new Promise(resolve => { finish = resolve; });
  });
  await page.refresh();
  const loading = page.onReachBottom();
  await page.filter({ currentTarget: { dataset: { index: 1 } } });
  finish(orders(['stale'], true));
  await loading;
  assert.deepEqual(calls.at(-1), { status: 'AWAITING_MERCHANT', q: '', page: 0 });
  assert.deepEqual(idsOf(page), ['waiting']);
  assert.equal(page.data.hasMore, false);
});
