const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');

const source = readFileSync(join(__dirname, '../pages/records/index.js'), 'utf8');
const receipt = { appId: 'wxMerchant', mchId: '1234567890', package: 'original+package/==' };
const event = { currentTarget: { dataset: { id: 'W-original' } } };

function setup({ platform = 'ios', runtimeApp = receipt.appId, nativeError } = {}) {
  let page;
  const requests = [], modals = [], toasts = [], calls = [];
  runInNewContext(source, {
    Page(definition) { page = definition; },
    require() {
      return { async request(path) { requests.push(path); return receipt; } };
    },
    wx: {
      getDeviceInfo() { return { platform }; },
      getAccountInfoSync() { return { miniProgram: { appId: runtimeApp } }; },
      canIUse() { return true; },
      showModal(options) { modals.push(options); },
      showToast(options) { toasts.push(options); },
      requestMerchantTransfer({ appId, mchId, package: originalPackage, success, fail }) {
        calls.push({ appId, mchId, package: originalPackage });
        if (nativeError) fail(nativeError);
        else success();
      }
    }
  });
  page.setData = values => Object.assign(page.data, values);
  page.refresh = async () => requests.push('refresh');
  return { page, requests, modals, toasts, calls };
}

test('开发者工具提示去手机确认，且不请求收款凭据', async () => {
  const { page, requests, modals, calls } = setup({ platform: 'devtools' });
  await page.confirmWithdrawal(event);
  assert.match(modals[0].content, /手机微信/);
  assert.deepEqual(requests, []);
  assert.deepEqual(calls, []);
});

test('手机端原样传递收款参数，退出微信页面后查最新状态', async () => {
  const { page, requests, calls, toasts } = setup();
  await page.confirmWithdrawal(event);
  assert.deepEqual(calls, [receipt]);
  assert.deepEqual(requests, ['/finance/withdrawals/W-original/confirmation', 'refresh']);
  assert.deepEqual(toasts, []);
  assert.equal(page.data.confirmingId, '');
});

test('运行中的小程序 AppID 不匹配时拒绝拉起微信收款页', async () => {
  const { page, calls, toasts } = setup({ runtimeApp: 'wxDifferent' });
  await page.confirmWithdrawal(event);
  assert.match(toasts[0].title, /小程序与原提现单不匹配/);
  assert.deepEqual(calls, []);
});

test('微信拉起失败时保留错误码并隐藏凭据，取消时只刷新', async () => {
  const rejected = setup({ nativeError: {
    errMsg: 'requestMerchantTransfer:fail system:access_denied ' + receipt.package,
    errno: 123
  } });
  await rejected.page.confirmWithdrawal(event);
  assert.match(rejected.modals[0].content, /system:access_denied.*错误码 123/);
  assert.equal(rejected.modals[0].content.includes(receipt.package), false);
  assert.equal(rejected.requests.at(-1), 'refresh');

  const cancelled = setup({ nativeError: { errMsg: 'requestMerchantTransfer:cancel' } });
  await cancelled.page.confirmWithdrawal(event);
  assert.deepEqual(cancelled.modals, []);
  assert.equal(cancelled.requests.at(-1), 'refresh');
});
