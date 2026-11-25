const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../pages/products/index.js'), 'utf8');
const ids = (page, field = 'rows') => Array.from(page.data[field], item => item.id);
const touch = y => ({ identifier: 1, clientY: y });
const handle = index => ({ currentTarget: { dataset: { index } } });
const product = (id, fields = {}) => ({ id, available: true, availableStock: 1, ...fields });

function sortPage(products, save = async () => {}) {
  let definition;
  let loads = 0;
  const calls = [];
  let menu;
  let toast;
  const query = {
    selectAll() { return this; },
    boundingClientRect() { return this; },
    exec(callback) {
      let top = 80;
      const slots = Array.from(page.data.visible, (_, index) => {
        const height = index % 2 ? 152 : 110;
        const slot = { top, bottom: top + height, height };
        top += height;
        return slot;
      });
      callback([slots]);
    }
  };
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    wx: {
      createSelectorQuery: () => query,
      showActionSheet(options) { menu = options; },
      showToast(options) { toast = options.title; }
    },
    require(name) {
      if (name.endsWith('/page')) return { register(value) { definition = value; } };
      if (name.endsWith('/ui')) {
        return { access: async () => ({ blocked: '', store: { id: 'shop' } }), money: String };
      }
      return {
        async request(url, body, method) {
          if (url === '/products') {
            loads++;
            return products;
          }
          calls.push({ url, method, before: Array.from(body.previousProductIds), after: Array.from(body.productIds) });
          return save();
        }
      };
    }
  });
  const page = {
    ...definition, data: { ...definition.data }, pageVisible: true,
    setData(state) { Object.assign(this.data, state); }
  };
  return { page, calls, loads: () => loads, menu: () => menu, toast: () => toast };
}

test('按住卡片手柄时锁定滚动与刷新，松手后才更新商品顺序', async () => {
  const products = ['a', 'b', 'c', 'd', 'e', 'f'].map(id => product(id, { available: id !== 'b' }));
  const { page, loads, calls, toast } = sortPage(products);
  await page.refresh();
  page.onScroll({ detail: { scrollTop: 55 } });
  page.startDrag({ ...handle(0), touches: [touch(132)] });
  assert.equal(page.data.dragLocked, true);
  assert.equal(page.data.scrollTop, 55);
  await page.pullRefresh();
  assert.equal(loads(), 1);
  assert.equal(page.data.refreshing, false);
  page.moveDrag({ touches: [touch(135)] });
  assert.equal(page.data.dragFrom, -1);
  page.moveDrag({ touches: [touch(195)] });
  assert.equal(page.data.dragTo, 0);
  page.moveDrag({ touches: [touch(210)] });
  assert.equal(page.data.dragTo, 1);
  page.onScroll({ detail: { scrollTop: 12 } });
  page.moveDrag({ touches: [touch(238)] });
  assert.equal(page.data.scrollTop, 55);
  assert.equal(page.scrollPosition, 55);
  assert.equal(page.data.dragOffset, 106);
  assert.equal(page.data.dragTo, 1);
  assert.deepEqual(ids(page), ['a', 'b', 'c', 'd', 'e', 'f']);
  page.endDrag({ changedTouches: [touch(238)] });
  assert.deepEqual(ids(page), ['b', 'a', 'c', 'd', 'e', 'f']);
  assert.equal(page.data.rows[0].available, false);
  assert.equal(page.data.dragFrom, -1);
  assert.equal(page.data.dragLocked, false);
  assert.equal(calls.length, 0);
  await page.saveOrder();
  assert.deepEqual(calls, [{
    url: '/products/display-order', method: 'PUT',
    before: ['a', 'b', 'c', 'd', 'e', 'f'], after: ['b', 'a', 'c', 'd', 'e', 'f']
  }]);
  assert.equal(page.data.orderChanged, false);
  assert.equal(toast(), '顺序已保存');
});

test('取消拖动保留原顺序，筛选列表可轻点上移且未显示的商品保留原位置', async () => {
  const { page, calls, menu } = sortPage([product('a'), product('b', { available: false }), product('c')]);
  await page.refresh();
  page.select(handle(1));
  assert.deepEqual(ids(page, 'visible'), ['a', 'c']);
  page.startDrag({ ...handle(1), touches: [touch(210)] });
  page.moveDrag({ touches: [touch(130)] });
  page.cancelDrag();
  assert.equal(page.data.dragLocked, false);
  assert.deepEqual(ids(page), ['a', 'b', 'c']);

  page.startDrag({ ...handle(1), touches: [touch(210)] });
  page.endDrag({ changedTouches: [touch(210)] });
  assert.equal(page.data.dragLocked, false);
  page.moveOptions(handle(1));
  assert.deepEqual(Array.from(menu().itemList), ['上移一位']);
  menu().success({ tapIndex: 0 });
  assert.deepEqual(ids(page, 'visible'), ['c', 'a']);
  page.select(handle(0));
  assert.deepEqual(ids(page, 'visible'), ['c', 'b', 'a']);
  page.cancelOrder();
  assert.deepEqual(ids(page), ['a', 'b', 'c']);
  assert.equal(calls.length, 0);
});

test('保存遇到网络错误保留草稿，列表冲突时需重新加载', async () => {
  let attempts = 0;
  const { page, calls } = sortPage([product('a'), product('b')], () => {
    if (++attempts === 1) throw new Error('网络错误');
    throw Object.assign(new Error('顺序已变化'), { status: 409 });
  });
  await page.refresh();
  page.startDrag({ ...handle(1), touches: [touch(210)] });
  page.moveDrag({ touches: [touch(130)] });
  page.endDrag({ changedTouches: [touch(130)] });
  await page.saveOrder();
  assert.equal(page.data.orderError, '网络错误');
  assert.deepEqual(ids(page), ['b', 'a']);
  await page.saveOrder();
  assert.equal(page.data.orderConflict, true);
  assert.deepEqual(ids(page), ['b', 'a']);
  await page.saveOrder();
  assert.equal(calls.length, 2);
  await page.reloadOrder();
  assert.equal(page.data.orderConflict, false);
  assert.deepEqual(ids(page), ['a', 'b']);
});

test('刷新后保留排序草稿和最新商品信息，检测到其他人改动时保留草稿并提示', async () => {
  const products = [product('a'), product('b')];
  const { page, menu } = sortPage(products);
  await page.refresh();
  page.moveOptions(handle(1));
  menu().success({ tapIndex: 0 });
  products[1].name = '改名后的商品';
  await page.pullRefresh();
  assert.deepEqual(ids(page), ['b', 'a']);
  assert.equal(page.data.rows[0].name, '改名后的商品');
  assert.equal(page.data.orderChanged, true);
  assert.equal(page.data.refreshing, false);

  products.push(product('c'));
  await page.refresh();
  assert.equal(page.data.orderConflict, true);
  assert.deepEqual(ids(page), ['b', 'a']);
  await page.reloadOrder();
  assert.deepEqual(ids(page), ['a', 'b', 'c']);
});
