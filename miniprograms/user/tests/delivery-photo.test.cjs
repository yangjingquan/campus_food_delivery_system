const test = require('node:test');
const assert = require('node:assert/strict');

global.wx = { getStorageSync: () => '' };
global.getApp = () => ({ ensureLogin: async () => {} });
const api = require('../utils/api');
let definition;
global.Page = value => { definition = value; };
require('../pages/order-detail/index');

function openOrder(t) {
  const photos = [];
  const previews = [];
  const order = { id: 'order-1', fulfillmentType: 'delivery', fulfillStatus: 'COMPLETED', deliveryPhotoAvailable: true };
  const session = { token: 'session-1' };
  t.mock.method(api, 'get', async () => ({ data: { ...order } }));
  global.wx = {
    getStorageSync: key => key === 'userToken' ? session.token : '',
    downloadFile: options => photos.push(options),
    previewImage: options => previews.push(options)
  };
  const page = {
    ...definition, orderId: order.id, data: { ...definition.data },
    setData(data) { Object.assign(this.data, data); }
  };
  return { page, order, session, photos, previews };
}

const downloaded = tempFilePath => ({ statusCode: 200, tempFilePath });
const settleRequests = () => new Promise(resolve => setImmediate(resolve));

test('已完成外卖订单直接加载照片，点图预览复用已下载的图片', async t => {
  const { page, order, photos, previews } = openOrder(t);
  const pending = page.load();
  await settleRequests();
  assert.equal(page.data.showDeliveryPhoto, true);
  assert.match(photos[0].url, /\/orders\/order-1\/delivery-photo$/);
  assert.equal(photos[0].header.Authorization, 'Bearer session-1');
  photos[0].success(downloaded('wxfile://photo.jpg'));
  await pending;
  assert.equal(page.data.photoPath, 'wxfile://photo.jpg');

  page.previewDeliveryPhoto();
  assert.deepEqual(previews[0].urls, ['wxfile://photo.jpg']);
  page.onHide();
  await page.load();
  assert.equal(photos.length, 1);

  for (const changes of [
    { fulfillmentType: 'pickup' },
    { fulfillmentType: 'delivery', fulfillStatus: 'DELIVERING' },
    { fulfillStatus: 'COMPLETED', deliveryPhotoAvailable: false }
  ]) {
    Object.assign(order, changes);
    await page.load();
    assert.equal(page.data.showDeliveryPhoto, false);
    assert.equal(page.data.photoPath, '');
    assert.equal(photos.length, 1);
  }
});

test('下载或图片解码失败后可以重新加载送达照片', async t => {
  const { page, photos } = openOrder(t);
  const first = page.load();
  await settleRequests();
  photos[0].fail();
  await first;
  assert.equal(page.data.photoLoadError, true);
  const retry = page.loadDeliveryPhoto();
  photos[1].success(downloaded('wxfile://photo.jpg'));
  await retry;
  page.deliveryPhotoError({ currentTarget: { dataset: { path: 'wxfile://photo.jpg' } } });
  assert.equal(page.data.photoPath, '');
  assert.equal(page.data.photoLoadError, true);
});

test('离开页面和登录会话变更后，旧照片响应不会被展示', async t => {
  const { page, photos, session } = openOrder(t);
  const first = page.load();
  await settleRequests();
  page.onHide();
  photos[0].success(downloaded('wxfile://hidden.jpg'));
  await first;
  assert.equal(page.data.photoPath, '');

  const second = page.load();
  await settleRequests();
  session.token = 'session-2';
  photos[1].success(downloaded('wxfile://old-session.jpg'));
  await second;
  assert.equal(page.data.photoPath, '');
  assert.equal(page.data.photoLoadError, true);
});
