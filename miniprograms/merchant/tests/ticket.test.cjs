const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../pages/ticket/index.js'), 'utf8');

function pageFor(printing, extra = {}) {
  const calls = [];
  let definition;
  const response = {
    id: 'order-1', paidCents: 1500, deliveryCents: 200, code: 'pickup-token',
    lines: [{ name: '套餐', priceCents: 1300, quantity: 1, amountCents: 1300 }],
    ...extra
  };
  if (printing !== undefined) response.printing = printing;
  const api = { request: async (url) => {
    calls.push(url);
    if (url.endsWith('/ticket')) return response;
    if (url === '/profile') return { store: { name: '测试商户' } };
    if (url === '/orders/order-1') return { delivery: { building: '一号楼', room: '101' } };
    throw new Error('不应发起的请求 ' + url);
  } };
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    require(name) {
      if (name.endsWith('/api')) return api;
      if (name.endsWith('/config')) return { apiEnvironment: 'development' };
      return {
        orderPath: id => '/orders/' + id,
        money: cents => '¥' + (cents / 100).toFixed(2),
        action: async (page, work) => work()
      };
    },
    wx: { getStorageSync() { return ''; } },
    clearTimeout, setTimeout
  });
  const page = {
    ...definition, data: { ...definition.data },
    setData(values) { Object.assign(this.data, values); }
  };
  page.onLoad({ id: 'order-1' });
  return { page, calls };
}

test('旧版接口缺少 printing 时可预览小票，并明确提示更新打印服务', async () => {
  const { page, calls } = pageFor(undefined);
  await page.refresh();
  assert.equal(page.data.error, '');
  assert.equal(page.data.loading, false);
  assert.equal(page.data.ticket.printing.status, 'unavailable');
  assert.equal(page.data.store.name, '测试商户');
  assert.equal(page.data.ticket.address, '一号楼 101');
  assert.equal(page.data.ticket.createdTime, '当前接口未提供');
  assert.equal(page.data.lines[0].unitPrice, '¥13.00');
  assert.equal(page.data.canPrint, false);
  assert.match(page.data.ticket.printing.message, /更新商户接口服务/);
  page.print();
  assert.equal(calls.some(url => url.endsWith('/print')), false);
});

test('空的或不完整的打印状态均可正常展示页面', async () => {
  for (const printing of [null, {}, { status: 'invalid' }]) {
    const { page } = pageFor(printing);
    await page.refresh();
    assert.equal(page.data.error, '');
    assert.equal(page.data.canPrint, false);
    assert.equal(page.data.ticket.printing.status, 'unavailable');
  }
});

test('新版接口保持真实状态和小票数据，不额外查询旧版元数据', async () => {
  const { page, calls } = pageFor({ status: 'submitted', message: '已提交', providerId: 'p-1' }, {
    merchantName: '商户 A', address: '二号楼 201', createdTime: '2026-11-19 19:00:00', orderQrImage: 'data:image/png;base64,example'
  });
  await page.refresh();
  assert.equal(page.data.canPrint, true);
  assert.equal(page.data.printPending, false);
  assert.equal(page.data.printLabel, '已提交打印');
  assert.equal(page.data.store.name, '商户 A');
  assert.equal(page.data.ticket.printing.providerId, 'p-1');
  assert.deepEqual(calls, ['/orders/order-1/ticket']);
});

test('新版接口没有历史打印任务时仍支持打印，排队时禁用重复提交', async () => {
  const { page } = pageFor({ status: 'none', message: '尚无打印任务' });
  await page.refresh();
  assert.equal(page.data.canPrint, true);
  assert.equal(page.data.printPending, false);
  const pending = pageFor({ status: 'waiting', message: '已排队' });
  await pending.page.refresh();
  assert.equal(pending.page.data.canPrint, true);
  assert.equal(pending.page.data.printPending, true);
  pending.page.print();
  assert.equal(pending.calls.some(url => url.endsWith('/print')), false);
});
