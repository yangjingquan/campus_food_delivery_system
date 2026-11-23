const test = require('node:test');
const assert = require('node:assert/strict');

global.wx = { getStorageSync: () => '' };
global.getApp = () => ({ ensureLogin: async () => {} });
const api = require('../utils/api');
let definition;
global.Component = value => { definition = value; };
require('../components/delivery-map/index');

function settleRequests() {
  return new Promise(resolve => setImmediate(resolve));
}

function createMap(t) {
  const events = [];
  const timers = new Map();
  const map = {
    ...definition.methods,
    data: { ...definition.data, orderId: 'order-1' },
    setData(data) { Object.assign(this.data, data); },
    triggerEvent(name) { events.push(name); }
  };
  t.mock.method(global, 'setTimeout', (callback, delay) => {
    const timer = Symbol();
    timers.set(timer, { callback, delay });
    return timer;
  });
  t.mock.method(global, 'clearTimeout', timer => timers.delete(timer));
  return {
    map, events, timers,
    async poll(delay) {
      assert.equal(timers.size, 1);
      const [timer, next] = timers.entries().next().value;
      assert.equal(next.delay, delay);
      timers.delete(timer);
      await next.callback();
    }
  };
}

test('配送地图首次展示后自动刷新，区分实时和最后已知位置，完成时清除位置', async t => {
  const view = createMap(t);
  const position = { latitude: '41.802', longitude: '123.431', expirySeconds: 20 };
  const states = [
    { state: 'waiting' },
    { state: 'live', ...position, capturedAt: 1760000000000 },
    { state: 'stale', ...position },
    { state: 'ended', ...position }
  ];
  t.mock.method(api, 'get', async path => {
    assert.equal(path, '/orders/order-1/location');
    return { data: states.shift() };
  });

  definition.lifetimes.attached.call(view.map);
  await settleRequests();
  assert.equal(view.map.data.markers.length, 0);
  await view.poll(15000);
  assert.equal(view.map.data.markers[0].latitude, 41.802);
  assert.equal(view.map.data.stale, false);
  assert.ok(view.map.data.updatedAt);
  await view.poll(10000);
  assert.equal(view.map.data.stale, true);
  assert.equal(view.map.data.markers[0].callout.content, '最后已知位置');
  await view.poll(10000);
  assert.deepEqual(view.map.data.markers, []);
  assert.equal(view.map.data.latitude, 0);
  assert.deepEqual(view.events, ['ended']);
  assert.equal(view.timers.size, 0);
});

test('配送地图页面隐藏或组件卸载后停止轮询，旧响应无法覆盖重新进入时的位置', async t => {
  const view = createMap(t);
  let completeOldRequest;
  let calls = 0;
  t.mock.method(api, 'get', async () => {
    if (++calls === 1) return new Promise(resolve => { completeOldRequest = resolve; });
    return { data: { state: 'live', latitude: 41.8, longitude: 123.4 } };
  });

  definition.lifetimes.attached.call(view.map);
  await settleRequests();
  definition.pageLifetimes.hide.call(view.map);
  assert.equal(view.timers.size, 0);
  definition.pageLifetimes.show.call(view.map);
  await settleRequests();
  completeOldRequest({ data: { state: 'live', latitude: 40, longitude: 123 } });
  await settleRequests();
  assert.equal(view.map.data.latitude, 41.8);
  assert.equal(view.timers.size, 1);
  definition.lifetimes.detached.call(view.map);
  assert.equal(view.timers.size, 0);
});

test('配送地图网络出错后自动重试，忽略无效坐标', async t => {
  const view = createMap(t);
  let calls = 0;
  t.mock.method(api, 'get', async () => {
    if (++calls === 1) throw new Error('网络暂不可用');
    return { data: { state: 'live', latitude: '', longitude: 123.4 } };
  });

  definition.lifetimes.attached.call(view.map);
  await settleRequests();
  assert.match(view.map.data.stateText, /自动重试/);
  await view.poll(15000);
  assert.deepEqual(view.map.data.markers, []);
  definition.lifetimes.detached.call(view.map);
});
