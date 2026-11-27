const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadUserPage(relPath) {
  let definition;
  global.Page = value => { definition = value; };
  const resolved = require.resolve(relPath);
  delete require.cache[resolved];
  require(relPath);
  return {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        if (key.includes('.')) {
          const [root, prop] = key.split('.');
          if (this.data[root]) this.data[root][prop] = value;
        } else {
          this.data[key] = value;
        }
      }
    },
    getTabBar() { return null; }
  };
}

test('确认订单页支持快捷备注去重、餐具底部面板选择，并将字段与幂等指纹提交到 /checkout', async () => {
  const storage = {
    shoppingCart: [
      { merchantId: 'M1', productId: 'P1', quantity: 2, availableStock: 10, priceCents: 1200, lineKey: 'P1' }
    ]
  };
  const submissions = [];
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = value; },
    removeStorageSync: key => { delete storage[key]; },
    navigateTo({ complete }) { complete?.(); },
    request({ url, data, success }) {
      if (url.endsWith('/catalog')) {
        success({ statusCode: 200, data: { merchants: [{ id: 'M1', open: true, minimumOrderCents: 1000, products: [{ id: 'P1', priceCents: 1200, availableStock: 10 }] }] } });
      } else if (url.endsWith('/addresses')) {
        success({ statusCode: 200, data: { rows: [{ id: 'A1', isDefault: true, latitude: 41.8, longitude: 123.4 }] } });
      } else if (url.endsWith('/checkout')) {
        submissions.push(data);
        success({ statusCode: 200, data: { payment: { id: 'PAY1', status: 'UNPAID' } } });
      }
    }
  };
  global.getApp = () => ({
    getConfig: async () => ({ deliveryFeeCents: 300, paymentMode: 'mock' }),
    ensureLogin: async () => ({ id: 9 })
  });

  const confirm = loadUserPage('../pages/confirm/index');
  confirm.onLoad({ merchantIds: 'M1' });
  await confirm.load();

  confirm.appendQuickRemark({ currentTarget: { dataset: { tag: '不要香菜' } } });
  confirm.appendQuickRemark({ currentTarget: { dataset: { tag: '不要香菜' } } });
  confirm.appendQuickRemark({ currentTarget: { dataset: { tag: '微辣' } } });
  assert.equal(confirm.data.remark, '不要香菜，微辣');

  confirm.chooseTableware();
  assert.equal(confirm.data.dialog.sheet, true);
  confirm.selectDialogOption({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(confirm.data.tablewareIndex, 1);

  await confirm.submit();
  assert.equal(submissions.length, 1);
  assert.equal(submissions[0].remark, '不要香菜，微辣');
  assert.equal(submissions[0].tableware, '无需餐具');
  const firstKey = submissions[0].requestKey;

  confirm.inputRemark({ detail: { value: '放宿舍楼下架子' } });
  await confirm.submit();
  assert.equal(submissions.length, 2);
  assert.equal(submissions[1].remark, '放宿舍楼下架子');
  assert.notEqual(submissions[1].requestKey, firstKey);
});

test('售后申请支持上传 PNG 凭证图片，售后详情页支持下载并预览凭证', async () => {
  const pngBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
  const storage = { userToken: 'tok_user' };
  const postedAftersales = [];
  const previewed = [];

  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = value; },
    redirectTo() {},
    previewImage({ current }) { previewed.push(current); },
    chooseMedia({ success, complete }) {
      success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/evidence.png', size: 512 }] });
      complete?.();
    },
    getFileSystemManager: () => ({
      readFile({ success }) { success({ data: pngBase64 }); }
    }),
    downloadFile({ url, header, success }) {
      assert.match(url, /\/aftersales\/R1\/photo$/);
      assert.equal(header.Authorization, 'Bearer tok_user');
      success({ statusCode: 200, tempFilePath: 'wxfile://tmp/downloaded-evidence.png', header: { 'content-type': 'image/png' } });
    },
    getImageInfo({ src, success }) {
      success({ path: src, width: 1, height: 1 });
    },
    request({ url, method, data, success }) {
      if (url.endsWith('/orders/O1')) {
        success({ statusCode: 200, data: {
          id: 'O1', mergedPaymentStatus: 'PAYMENT_SUCCEEDED', accepted: true, fulfillStatus: 'DELIVERING',
          paidCents: 2000, foodCents: 1700, deliveryFeeCents: 300,
          refundedCents: 0, foodRefundedCents: 0, partialDeliveryRefundCents: 0, aftersales: []
        } });
      } else if (url.endsWith('/orders/O1/aftersales') && method === 'POST') {
        postedAftersales.push(data);
        success({ statusCode: 200, data: { id: 'R1' } });
      } else if (url.endsWith('/aftersales/R1')) {
        success({ statusCode: 200, data: {
          id: 'R1', status: 'AWAITING_MERCHANT_DECISION', amountCents: 1000, reason: '餐品有问题',
          description: '汤洒了', hasPhoto: true, createdAt: '2026-11-27T08:00:00Z', events: []
        } });
      }
    }
  };
  global.getApp = () => ({ ensureLogin: async () => ({ id: 1 }) });

  const refund = loadUserPage('../pages/refund/index');
  refund.onLoad({ id: 'O1' });
  await new Promise(resolve => setImmediate(resolve));
  refund.inputAmount({ detail: { value: '10.00' } });
  refund.choosePhoto();
  assert.equal(refund.data.photoPreview, 'wxfile://tmp/evidence.png');
  assert.equal(refund.data.photoDataUrl, `data:image/png;base64,${pngBase64}`);
  await refund.submit();
  assert.equal(postedAftersales.length, 1);
  assert.equal(postedAftersales[0].photo, `data:image/png;base64,${pngBase64}`);

  const aftersale = loadUserPage('../pages/aftersale/index');
  aftersale.onLoad({ id: 'R1' });
  await aftersale.load();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(aftersale.data.photoPath, 'wxfile://tmp/downloaded-evidence.png');
  aftersale.previewPhoto();
  assert.deepEqual(previewed, ['wxfile://tmp/downloaded-evidence.png']);
});

test('订单详情页暴露商家与配送中骑手拨号能力，并保留备注与餐具字段', async () => {
  const dialed = [];
  global.wx = {
    getStorageSync: key => (key === 'userToken' ? 'tok_1' : ''),
    makePhoneCall({ phoneNumber }) { dialed.push(phoneNumber); },
    request({ url, success }) {
      if (url.endsWith('/orders/O9')) {
        success({ statusCode: 200, data: {
          id: 'O9', fulfillStatus: 'DELIVERING', fulfillmentType: 'delivery',
          paidCents: 1800, foodCents: 1500, deliveryFeeCents: 300, refundedCents: 0,
          phone: '13812345678', merchantPhone: '13900001111', riderPhone: '13700002222', riderName: '李同学',
          remark: '不要葱', tableware: '无需餐具', items: [], aftersales: []
        } });
      }
    }
  };
  global.getApp = () => ({ ensureLogin: async () => ({ id: 1 }) });

  const detail = loadUserPage('../pages/order-detail/index');
  detail.onLoad({ id: 'O9' });
  await detail.load();

  assert.equal(detail.data.canCallMerchant, true);
  assert.equal(detail.data.canCallRider, true);
  assert.equal(detail.data.order.remark, '不要葱');
  assert.equal(detail.data.order.tableware, '无需餐具');
  detail.callMerchant();
  detail.callRider();
  assert.deepEqual(dialed, ['13900001111', '13700002222']);
});

test('商户端宿主页原位切换 Tab 不触发 redirectTo，提现页支持全部提现填充', async () => {
  const ordersSource = fs.readFileSync(path.join(__dirname, '../merchant/pages/orders/index.js'), 'utf8');
  let ordersDef;
  let redirected = false;
  let paneRefreshed = 0;

  vm.runInNewContext(ordersSource, {
    wx: {
      redirectTo() { redirected = true; },
      setNavigationBarTitle() {},
      pageScrollTo() {}
    },
    require(name) {
      if (name.endsWith('/page')) return { register(val) { ordersDef = val; } };
      if (name.endsWith('/api')) return { request: async () => ({ rows: [], hasMore: false, counts: {} }) };
      return { access: async () => ({ blocked: '' }), money: String, date: String };
    }
  });

  const ordersPage = {
    ...ordersDef,
    data: { ...ordersDef.data },
    setData(patch) { Object.assign(this.data, patch); },
    selectComponent(selector) {
      if (selector === '#products-pane') return { refresh() { paneRefreshed += 1; } };
      return null;
    }
  };
  ordersPage.switchMerchantTab({ detail: { key: 'products' } });
  assert.equal(redirected, false);
  assert.equal(ordersPage.data.activeTab, 'products');
  assert.equal(ordersPage.data.selectedTabPath, '/merchant/pages/products/index');
  await ordersPage.onPullDownRefresh();
  assert.equal(paneRefreshed, 1);

  const withdrawSource = fs.readFileSync(path.join(__dirname, '../merchant/pages/withdraw/index.js'), 'utf8');
  let withdrawDef;
  vm.runInNewContext(withdrawSource, {
    wx: { getStorageSync: () => null },
    require(name) {
      if (name.endsWith('/page')) return { register(val) { withdrawDef = val; } };
      if (name.endsWith('/api')) {
        return { request: async () => ({ account: { availableCents: 5680 }, debtCents: 680, merchantId: 'M1', withdrawalIdentityId: '7' }) };
      }
      return { money: v => `¥${(v / 100).toFixed(2)}` };
    }
  });
  const withdrawPage = {
    ...withdrawDef,
    data: { ...withdrawDef.data },
    setData(patch) { Object.assign(this.data, patch); }
  };
  await withdrawPage.refresh();
  withdrawPage.withdrawAll();
  assert.equal(withdrawPage.data.amount, '50.00');
});

test('骑手端宿主页原位切换 Tab、订单页内切换历史子视图，扫码领单写入 scanResult 闭环', async () => {
  const riderPageSource = fs.readFileSync(path.join(__dirname, '../rider/utils/page.js'), 'utf8');
  let homeDef;
  const navigated = [];
  let relaunched = false;
  const appState = {};

  vm.runInNewContext(riderPageSource, {
    module: { exports: {} },
    Page(val) { homeDef = val; },
    getApp: () => appState,
    wx: {
      reLaunch() { relaunched = true; },
      navigateTo({ url }) { navigated.push(url); },
      redirectTo({ url }) { navigated.push('redirect:' + url); },
      setNavigationBarTitle() {},
      pageScrollTo() {},
      hideHomeButton() {},
      showToast() {}
    },
    require(name) {
      if (name.endsWith('/api')) {
        return {
          wxCall: async method => {
            if (method === 'scanCode') return { result: 'QR_TOKEN_1' };
            return {};
          },
          request: async (url, method) => {
            if (url === '/profile') return { id: 3, name: '王骑手', phone: '13600003333', auditStatus: 'APPROVED' };
            if (url === '/orders?scope=active') return [];
            if (url === '/orders?scope=history&offset=0') {
              return [{ id: 'H1', status: 'COMPLETED', building: '一公寓', room: '201', products: [] }];
            }
            if (url === '/batches') return { waitingCount: 0, batches: [] };
            if (url === '/finance') return { availableCents: 0, pendingCents: 0, heldCents: 0, frozenCents: 0, dueCents: 0, paidOutCents: 0, feeCents: 0, commissionCents: 0, orderCount: 0 };
            if (url === '/pickup' && method === 'POST') return { orderId: 'ORD_SCAN_1' };
            if (url === '/orders/ORD_SCAN_1') {
              return { id: 'ORD_SCAN_1', status: 'DELIVERING', building: '二公寓', room: '305', merchantName: '好味轩', products: [{ name: '牛肉面', quantity: 1 }] };
            }
            return {};
          }
        };
      }
      if (name.endsWith('./display')) return require('../rider/utils/display');
      if (name.endsWith('./location')) {
        return { listen: () => () => {}, authorize: async () => true, sample() {}, stop() {} };
      }
      if (name.endsWith('/roles')) {
        return { activeRole: () => 'rider', activate() {}, attachRiderLocation() {}, showVerification: async () => {}, openPicker() {}, forget() {} };
      }
      throw new Error('Unexpected require: ' + name);
    }
  }).register('home');

  const rider = {
    ...homeDef,
    rolePageVisible: true,
    data: JSON.parse(JSON.stringify(homeDef.data)),
    setData(patch) { Object.assign(this.data, patch); }
  };

  rider.switchRiderTab({ detail: { page: 'orders' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(relaunched, false);
  assert.equal(rider.data.activeTab, 'orders');

  await rider.switchOrdersSubTab({ currentTarget: { dataset: { subtab: 'history' } } });
  assert.equal(rider.data.ordersSubTab, 'history');
  assert.equal(rider.data.historyVisibleItems.length, 1);
  assert.equal(rider.data.historyVisibleItems[0].id, 'H1');

  await rider.scan();
  assert.equal(appState.scanResult.ok, true);
  assert.equal(appState.scanResult.order.id, 'ORD_SCAN_1');
  assert.deepEqual(navigated, ['/rider/pages/scanResult/index']);
});
