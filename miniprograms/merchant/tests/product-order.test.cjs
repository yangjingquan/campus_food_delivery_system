const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../pages/product-order/index.js'), 'utf8');
const ids = page => Array.from(page.data.rows, item => item.id);
const touch = y => ({ identifier: 1, clientY: y });
const handle = index => ({ currentTarget: { dataset: { index } } });

function sortPage(products, save = async () => {}) {
  let definition;
  let tick;
  const calls = [];
  let menu;
  let navigations = 0;
  const query = {
    select() { return this; },
    fields() { return this; },
    boundingClientRect() { return this; },
    exec(callback) {
      callback([{ top: 70, bottom: 220, height: 150, scrollTop: 0 }, { height: 68 }]);
    }
  };
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    setInterval(callback) { tick = callback; return 1; },
    clearInterval() { tick = undefined; },
    wx: {
      createSelectorQuery: () => query,
      showActionSheet(options) { menu = options; },
      showToast() {},
      navigateBack() { navigations++; }
    },
    require: () => ({
      async request(url, body, method) {
        if (url === '/products') return products;
        calls.push({ url, method, before: Array.from(body.previousProductIds), after: Array.from(body.productIds) });
        return save();
      }
    })
  });
  const page = {
    ...definition, data: { ...definition.data },
    setData(state) { Object.assign(this.data, state); }
  };
  return { page, calls, tick: () => tick?.(), menu: () => menu, navigations: () => navigations };
}

test('拖到列表边缘继续滚动，松手才更新包括已下架商品在内的顺序', async () => {
  const products = ['a', 'b', 'c', 'd', 'e', 'f'].map(id => ({ id, available: id !== 'b' }));
  const { page, tick, calls, navigations } = sortPage(products);
  await page.refresh();
  page.startDrag({ ...handle(0), touches: [touch(96)] });
  page.moveDrag({ touches: [touch(99)] });
  assert.equal(page.data.dragFrom, -1);
  page.moveDrag({ touches: [touch(218)] });
  for (let index = 0; index < 20; index++) tick();
  assert.equal(page.data.scrollTop, 258);
  assert.equal(page.data.dragTo, 5);
  assert.deepEqual(ids(page), ['a', 'b', 'c', 'd', 'e', 'f']);
  page.endDrag({ changedTouches: [touch(218)] });
  assert.deepEqual(ids(page), ['b', 'c', 'd', 'e', 'f', 'a']);
  assert.equal(page.data.rows[0].available, false);
  assert.equal(page.data.dragFrom, -1);
  assert.equal(calls.length, 0);
  await page.save();
  assert.deepEqual(calls, [{
    url: '/products/display-order', method: 'PUT',
    before: ['a', 'b', 'c', 'd', 'e', 'f'], after: ['b', 'c', 'd', 'e', 'f', 'a']
  }]);
  assert.equal(navigations(), 1);
});

test('取消拖动保留原顺序，轻点手柄可上移商品，取消编辑不提交', async () => {
  const { page, calls, menu, navigations } = sortPage([{ id: 'a' }, { id: 'b' }]);
  await page.refresh();
  page.startDrag({ ...handle(1), touches: [touch(150)] });
  page.moveDrag({ touches: [touch(80)] });
  page.cancelDrag();
  assert.deepEqual(ids(page), ['a', 'b']);

  page.startDrag({ ...handle(1), touches: [touch(150)] });
  page.endDrag({ changedTouches: [touch(150)] });
  page.moveOptions(handle(1));
  assert.deepEqual(Array.from(menu().itemList), ['上移一位']);
  menu().success({ tapIndex: 0 });
  assert.deepEqual(ids(page), ['b', 'a']);
  page.cancel();
  assert.equal(navigations(), 1);
  assert.equal(calls.length, 0);
});

test('保存遇到网络错误保留草稿，列表冲突时需重新加载', async () => {
  let attempts = 0;
  const { page, calls, navigations } = sortPage([{ id: 'a' }, { id: 'b' }], () => {
    if (++attempts === 1) throw new Error('网络错误');
    throw Object.assign(new Error('顺序已变化'), { status: 409 });
  });
  await page.refresh();
  page.startDrag({ ...handle(1), touches: [touch(150)] });
  page.moveDrag({ touches: [touch(80)] });
  page.endDrag({ changedTouches: [touch(80)] });
  await page.save();
  assert.equal(page.data.saveError, '网络错误');
  assert.deepEqual(ids(page), ['b', 'a']);
  await page.save();
  assert.equal(page.data.conflict, true);
  assert.deepEqual(ids(page), ['b', 'a']);
  await page.save();
  assert.equal(calls.length, 2);
  assert.equal(navigations(), 0);
  await page.refresh();
  assert.equal(page.data.conflict, false);
  assert.deepEqual(ids(page), ['a', 'b']);
});
