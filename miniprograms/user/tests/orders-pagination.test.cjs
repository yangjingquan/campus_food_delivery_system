const test = require('node:test');
const assert = require('node:assert/strict');

test('订单触底追加、失败重试，刷新或切换筛选不会混入旧页', async () => {
  const requests = [];
  let refreshStopped = false;
  global.wx = {
    getStorageSync() {},
    pageScrollTo() {},
    stopPullDownRefresh() { refreshStopped = true; },
    request(options) { requests.push(options); }
  };
  global.getApp = () => ({ ensureLogin: async () => {} });
  let definition;
  global.Page = page => { definition = page; };
  require('../pages/orders/index');
  const page = {
    ...definition, data: { ...definition.data },
    setData(patch) { Object.assign(this.data, patch); }
  };

  async function request(status, number) {
    await Promise.resolve();
    const options = requests.shift();
    const query = new URL(options.url).searchParams;
    assert.equal(query.get('status'), status);
    assert.equal(query.get('page'), String(number));
    return options;
  }
  function respond(options, ids, hasMore) {
    options.success({
      statusCode: 200,
      data: { rows: ids.map(id => ({ id, paidCents: 1000 })), hasMore }
    });
  }

  const first = page.load();
  respond(await request('全部', 1), ['a', 'b'], true);
  await first;
  const more = page.onReachBottom();
  const second = await request('全部', 2);
  assert.equal(page.onReachBottom(), undefined);
  second.fail();
  await more;
  assert.ok(page.data.loadMoreError);
  assert.deepEqual(page.data.visible.map(row => row.id), ['a', 'b']);

  const retry = page.loadMore();
  respond(await request('全部', 2), ['b', 'c'], true);
  await retry;
  assert.deepEqual(page.data.visible.map(row => row.id), ['a', 'b', 'c']);
  const stale = page.onReachBottom();
  const third = await request('全部', 3);
  const filter = page.chooseFilter({ currentTarget: { dataset: { value: '售后' } } });
  respond(await request('售后', 1), ['refund'], false);
  await filter;
  respond(third, ['old'], false);
  await stale;
  assert.deepEqual(page.data.visible.map(row => row.id), ['refund']);
  assert.equal(page.onReachBottom(), undefined);
  assert.equal(requests.length, 0);

  const refresh = page.onPullDownRefresh();
  respond(await request('售后', 1), ['new'], false);
  await refresh;
  assert.equal(refreshStopped, true);
  assert.deepEqual(page.data.visible.map(row => row.id), ['new']);
});
