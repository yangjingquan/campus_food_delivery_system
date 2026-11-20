const test = require('node:test');
const assert = require('node:assert/strict');

test('扫码自取走单店免配送结算，发起一次支付并保留外卖购物车', async () => {
  const product = { id: 'P1', name: '拌面', priceCents: 700, availableStock: 5 };
  const merchant = {
    id: 'M03', name: '店铺', address: '学校东门', open: true,
    minimumOrderCents: 1500, deliveryFeeCents: 400, products: [product]
  };
  const deliveryAttempt = { requestKey: 'delivery_checkout' };
  const storage = {
    checkoutAttempt: deliveryAttempt,
    shoppingCart: [{ productId: 'P1', merchantId: 'M03', quantity: 1, priceCents: 700 }]
  };
  const paths = [];
  const requests = [];
  let wechatPayments = 0;
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = value; },
    removeStorageSync: key => { delete storage[key]; },
    setNavigationBarTitle() {},
    navigateTo({ url, complete }) { paths.push(url); complete?.(); },
    redirectTo({ url }) { paths.push(url); },
    requestPayment({ success }) { wechatPayments++; success(); },
    request({ url, data, success }) {
      const path = url.split('/v1')[1];
      requests.push({ path, data });
      let body;
      if (path.startsWith('/catalog')) body = { merchants: [merchant] };
      if (path === '/checkout') body = { payment: { id: 'pay', status: '未支付' } };
      if (path === '/payments/pay') body = {
        status: '未支付', expiresAt: Date.now() + 60_000, amountCents: 700, mode: 'wechat',
        merchantOrders: [{ fulfillmentType: 'pickup', amountCents: 700 }]
      };
      if (path === '/payments/pay/prepare') body = {};
      success({ statusCode: 200, data: body });
    }
  };
  global.getApp = () => ({
    getConfig: async () => ({ deliveryFeeCents: 400, paymentMode: 'wechat' }),
    ensureLogin: async () => ({ id: 8 })
  });
  function page(module) {
    let definition;
    global.Page = value => { definition = value; };
    require(module);
    return { ...definition, data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch); } };
  }
  const options = path => Object.fromEntries(new URLSearchParams(path.split('?')[1]));

  const store = page('../pages/store/index');
  store.onLoad({ scene: 'M03' });
  await store.load();
  assert.equal(store.data.fulfillmentType, 'pickup');
  assert.equal(store.data.selectedCount, 0);
  store.change({ currentTarget: { dataset: { id: 'P1', delta: 1 } } });
  store.checkout();
  assert.equal(paths[0], '/pages/confirm/index?merchantId=M03&fulfillmentType=pickup');

  const confirm = page('../pages/confirm/index');
  confirm.onLoad(options(paths[0]));
  await confirm.load();
  assert.equal(confirm.data.pickupAddress, '学校东门');
  assert.equal(confirm.data.totalText, '¥7.00');
  assert.equal(confirm.data.belowMinimum, false);
  await confirm.submit();
  const checkout = requests.find(entry => entry.path === '/checkout').data;
  assert.equal(checkout.fulfillmentType, 'pickup');
  assert.equal(checkout.addressId, undefined);
  assert.equal(checkout.expectedTotalCents, 700);
  assert.equal(storage.pickupCheckoutAttempt.paymentId, 'pay');
  assert.ok(!requests.some(entry => entry.path === '/addresses'));

  const payment = page('../pages/payment/index');
  payment.onLoad(options(paths[1]));
  payment.visible = true;
  await payment.load();
  await payment.load();
  assert.equal(wechatPayments, 1);
  assert.equal(paths[2], '/pages/payment-result/index?id=pay');

  const result = page('../pages/payment-result/index');
  result.paymentId = 'pay';
  result.removePaidItems(8);
  assert.deepEqual(storage.pickupCart, []);
  assert.equal(storage.shoppingCart[0].quantity, 1);
  assert.equal(storage.pickupCheckoutAttempt, undefined);
  assert.equal(storage.checkoutAttempt, deliveryAttempt);
});
