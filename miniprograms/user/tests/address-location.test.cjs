const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { hasCoordinates } = require('../utils/coordinates');

function editor(overrides = {}) {
  let definition;
  const calls = [];
  const wx = { getStorageSync() {}, setStorageSync() {}, navigateBack() {}, ...overrides };
  const api = { post: async (url, body) => { calls.push(body); return { data: { ...body, id: 'saved' } }; } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/address-edit/index.js'), 'utf8'), {
    Page: value => { definition = value; }, wx,
    require: id => id.endsWith('/api') ? api : { hasCoordinates }
  });
  const page = { ...definition, data: { ...definition.data }, setData(value) { Object.assign(this.data, value); } };
  return { page, calls };
}

test('空坐标、非数值和越界坐标不当作有效收餐位置', () => {
  for (const [latitude, longitude] of [[null, 123], [true, 123], ['', 123], ['NaN', 123], [91, 123], [41, 181]]) {
    assert.equal(hasCoordinates({ latitude, longitude }), false);
  }
  assert.equal(hasCoordinates({ latitude: 0, longitude: 0 }), true);
});

test('选择地图位置后提交实际经纬度，更换楼栋须重新选点', async () => {
  let options;
  const { page, calls } = editor({ chooseLocation: value => { options = value; } });
  page.setData({ name: '同学', phone: '13800138000', room: '101' });
  page.chooseLocation();
  assert.equal(page.data.choosingLocation, true);
  options.success({ name: 'H9号楼', latitude: 41.8123456, longitude: 123.4123456 });
  options.complete();
  await page.save();
  assert.equal(calls[0].latitude, 41.8123456);
  assert.equal(calls[0].longitude, 123.4123456);
  assert.equal(calls[0].building, 'H9号楼');
  page.input({ currentTarget: { dataset: { field: 'room' } }, detail: { value: '102' } });
  assert.equal(page.data.hasLocation, true);
  page.input({ currentTarget: { dataset: { field: 'building' } }, detail: { value: '另一楼栋' } });
  assert.equal(page.data.hasLocation, false);
  await page.save();
  assert.equal(calls.length, 1);
});

test('取消重新选点保留原坐标，拒绝授权时给出设置入口', () => {
  let options;
  const { page } = editor({ chooseLocation: value => { options = value; } });
  page.setData({ latitude: 41.8, longitude: 123.4, hasLocation: true });
  page.chooseLocation();
  assert.equal(options.latitude, 41.8);
  options.fail({ errMsg: 'chooseLocation:fail cancel' });
  options.complete();
  assert.equal(page.data.latitude, 41.8);
  assert.equal(page.data.error, '');
  page.chooseLocation();
  options.fail({ errMsg: 'chooseLocation:fail auth deny' });
  options.complete();
  assert.equal(page.data.locationPermissionDenied, true);
});

test('旧地址缺少位置时禁止直接保存', async () => {
  const { page, calls } = editor();
  page.setData({ name: '同学', phone: '13800138000', building: 'H9号楼', room: '101' });
  await page.save();
  assert.equal(calls.length, 0);
  assert.match(page.data.error, /地图/);
});

test('结算时缺少位置的旧地址引导编辑，不创建支付请求', async () => {
  let definition;
  let destination;
  let submitted = false;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/confirm/index.js'), 'utf8'), {
    Page: value => { definition = value; },
    wx: { navigateTo: value => { destination = value.url; } },
    require: id => {
      if (id.endsWith('/coordinates')) return { hasCoordinates };
      if (id.endsWith('/api')) return { post: () => { submitted = true; } };
      return {};
    }
  });
  const page = { ...definition, data: { paymentEnabled: true, address: { id: 'legacy-address' } } };
  await page.submit();
  assert.equal(destination, '/pages/address-edit/index?id=legacy-address');
  assert.equal(submitted, false);
});

test('地址列表选择旧地址时引导补充位置，已选点地址可正常使用', () => {
  let definition;
  let destination;
  let stored;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/addresses/index.js'), 'utf8'), {
    Page: value => { definition = value; },
    wx: { navigateTo: value => { destination = value.url; }, setStorageSync: (key, value) => { stored = value; } },
    require: id => id.endsWith('/coordinates') ? { hasCoordinates } : {}
  });
  const old = { id: 'legacy' };
  const ready = { id: 'ready', latitude: 41.8, longitude: 123.4 };
  const page = { ...definition, data: { rows: [old, ready] }, setData(value) { Object.assign(this.data, value); } };
  page.select({ currentTarget: { dataset: { id: 'legacy' } } });
  assert.equal(destination, '/pages/address-edit/index?id=legacy');
  assert.equal(stored, undefined);
  page.select({ currentTarget: { dataset: { id: 'ready' } } });
  assert.equal(stored, ready);
  assert.equal(page.data.selectedId, 'ready');
});
