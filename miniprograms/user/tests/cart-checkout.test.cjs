const test = require('node:test');
const assert = require('node:assert/strict');

test('购物车实时显示全车预估金额，只结算达标商户', async () => {
  const merchants = [
    { id: 'A', open: true, minimumOrderCents: 1300, products: [{ id: 'pA', priceCents: 1000, availableStock: 5 }] },
    { id: 'B', open: true, minimumOrderCents: 1800, products: [{ id: 'pB', priceCents: 1800, availableStock: 5 }] },
    { id: 'C', open: true, minimumOrderCents: 0, products: [{ id: 'pC', priceCents: 600, availableStock: 5 }] }
  ];
  const storage = {
    shoppingCart: merchants.map(merchant => {
      const product = merchant.products[0];
      return {
        merchantId: merchant.id, productId: product.id, quantity: 1, availableStock: 5,
        priceCents: 2000, lineKey: product.id
      };
    })
  };
  const address = { id: 'address', isDefault: true, latitude: 41.8, longitude: 123.4 };
  const destinations = [];
  const submissions = [];
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = value; },
    removeStorageSync: key => { delete storage[key]; },
    navigateTo({ url, complete }) { destinations.push(url); complete?.(); },
    request({ url, data, success }) {
      let body;
      if (url.endsWith('/catalog')) body = { merchants };
      if (url.endsWith('/addresses')) body = { rows: [address] };
      if (url.endsWith('/checkout')) {
        submissions.push(data);
        body = { payment: { id: 'payment', status: 'UNPAID' } };
      }
      success({ statusCode: 200, data: body });
    }
  };
  global.getApp = () => ({
    getConfig: async () => ({ deliveryFeeCents: 400, paymentMode: 'mock' }),
    ensureLogin: async () => ({ id: 1 })
  });
  function loadPage(path) {
    let definition;
    global.Page = value => { definition = value; };
    require(path);
    return {
      ...definition, data: { ...definition.data },
      setData(patch) { Object.assign(this.data, patch); },
      getTabBar() { return null; }
    };
  }

  const cart = loadPage('../pages/cart/index');
  await cart.load();
  assert.equal(cart.data.groups[0].shortfallText, '¥3.00');
  assert.equal(cart.data.checkoutCount, 2);
  assert.equal(cart.data.foodText, '¥34.00');
  assert.equal(cart.data.feeText, '¥12.00');
  assert.equal(cart.data.totalText, '¥46.00');
  cart.checkout();
  assert.equal(destinations[0], '/pages/confirm/index?merchantIds=B,C');

  const confirm = loadPage('../pages/confirm/index');
  const query = Object.fromEntries(new URLSearchParams(destinations[0].split('?')[1]));
  confirm.onLoad(query);
  await confirm.load();
  assert.deepEqual(confirm.data.groups.map(group => group.merchantId), ['B', 'C']);
  await confirm.submit();
  assert.deepEqual(submissions[0].items, [{ productId: 'pB', quantity: 1 }, { productId: 'pC', quantity: 1 }]);
  assert.equal(submissions[0].expectedTotalCents, 3200);

  const result = loadPage('../pages/payment-result/index');
  result.paymentId = 'payment';
  result.removePaidItems(1);
  await cart.load();
  assert.deepEqual(storage.shoppingCart.map(item => item.productId), ['pA']);
  assert.equal(cart.data.checkoutCount, 0);
  assert.equal(cart.data.foodText, '¥10.00');
  assert.equal(cart.data.feeText, '¥4.00');
  assert.equal(cart.data.totalText, '¥14.00');
  cart.checkout();
  assert.equal(destinations.length, 2);

  cart.change({ currentTarget: { dataset: { id: 'pA', delta: 1 } } });
  assert.equal(cart.data.checkoutCount, 1);
  assert.equal(cart.data.foodText, '¥20.00');
  assert.equal(cart.data.feeText, '¥4.00');
  assert.equal(cart.data.totalText, '¥24.00');
  cart.checkout();
  assert.equal(destinations[2], '/pages/confirm/index?merchantIds=A');

  cart.change({ currentTarget: { dataset: { id: 'pA', delta: -1 } } });
  assert.equal(cart.data.checkoutCount, 0);
  assert.equal(cart.data.totalText, '¥14.00');
});
