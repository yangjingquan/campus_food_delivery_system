const test = require('node:test');
const assert = require('node:assert/strict');
const merchant = require('../utils/merchant/api');
const rider = require('../utils/rider/api');

function setup(t, saved) {
  const storage = new Map(saved ? [['last-workspace-role', saved]] : []);
  const routes = [];
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    reLaunch({ url, success }) { routes.push(url); success(); },
    redirectTo({ url, success }) { routes.push(url); success(); }
  };
  const modulePath = require.resolve('../utils/roles');
  delete require.cache[modulePath];
  t.after(() => { delete global.wx; });
  return { roles: require(modulePath), routes, storage };
}

test('首次冷启动进入用户端，普通重开恢复角色，外部入口优先', t => {
  const { roles, storage } = setup(t);
  assert.equal(roles.rememberedRole({ scene: 1001 }), 'user');
  storage.set('last-workspace-role', 'rider');
  assert.equal(roles.rememberedRole({ path: 'pages/home/index', scene: 1089 }), 'rider');
  assert.equal(roles.rememberedRole({ path: 'pages/home/index', scene: 1007 }), 'user');
  assert.equal(roles.rememberedRole({ path: 'pages/store/index', query: { scene: 'M1' }, scene: 1047 }), 'user');
  assert.equal(roles.rememberedRole({ path: 'rider/pages/waiting/index', scene: 1107 }), 'user');
});

test('已通过审核直接跳到工作台，切换后保留各端会话', async t => {
  const { roles, routes, storage } = setup(t);
  t.mock.method(merchant, 'profile', async () => ({ store: { auditStatus: 'APPROVED' } }));
  t.mock.method(rider, 'request', async () => ({ auditStatus: 'APPROVED' }));
  storage.set('userToken', 'user-session');
  await roles.enter('merchant');
  await roles.enter('rider');
  assert.deepEqual(routes, ['/merchant/pages/orders/index', '/rider/pages/home/index']);
  assert.equal(storage.get('last-workspace-role'), 'rider');
  assert.equal(storage.get('userToken'), 'user-session');
});

test('未入驻、待审核、被驳回走原流程，保留已进入过的工作台', async t => {
  const { roles, routes, storage } = setup(t, 'user');
  t.mock.method(merchant, 'profile', async () => ({ store: null }));
  let auditStatus = 'NOT_ENROLLED';
  t.mock.method(rider, 'request', async () => ({ auditStatus }));
  await roles.enter('merchant');
  await roles.enter('rider');
  auditStatus = 'PENDING_REVIEW';
  await roles.enter('rider');
  auditStatus = 'REJECTED';
  await roles.enter('rider');
  assert.deepEqual(routes, [
    '/merchant/pages/onboarding/index', '/rider/pages/join/index',
    '/rider/pages/audit/index', '/rider/pages/audit/index'
  ]);
  assert.equal(storage.get('last-workspace-role'), 'user');
});

test('网络异常及返回按钮均可阻止角色跳转', async t => {
  const { roles, routes, storage } = setup(t, 'user');
  const read = t.mock.method(merchant, 'profile', async () => { throw new Error('网络错误'); });
  await assert.rejects(roles.enter('merchant'), /网络错误/);
  read.mock.mockImplementation(async () => ({ store: { auditStatus: 'APPROVED' } }));
  let visibleChecks = 0;
  assert.equal(await roles.enter('merchant', () => ++visibleChecks === 1), false);
  assert.deepEqual(routes, []);
  assert.equal(storage.get('last-workspace-role'), 'user');
});

test('微信导航回调超时时恢复原角色，允许重新切换', async t => {
  const { roles, storage } = setup(t, 'user');
  t.mock.method(merchant, 'profile', async () => ({ store: { auditStatus: 'APPROVED' } }));
  t.mock.timers.enable({ apis: ['setTimeout'] });
  global.getCurrentPages = () => [{ route: 'pages/roles/index' }];
  t.after(() => { delete global.getCurrentPages; });
  const started = new Promise(resolve => { wx.reLaunch = resolve; });
  const failed = assert.rejects(roles.enter('merchant'), /页面打开超时/);
  await started;
  t.mock.timers.tick(10000);
  await failed;
  assert.equal(roles.activeRole(), 'user');
  assert.equal(storage.get('last-workspace-role'), 'user');
});

test('配送中取消切换保留定位，确认时停止，切回时恢复', async t => {
  const { roles, routes } = setup(t);
  const actions = [];
  const location = {
    start() { actions.push('start'); },
    stop() { actions.push('stop'); },
    async hasActiveDelivery() { return true; }
  };
  roles.attachRiderLocation(location);
  roles.activate('rider');
  let confirm = false;
  wx.showModal = ({ success }) => success({ confirm });
  assert.equal(await roles.enter('user'), false);
  assert.deepEqual(actions, ['start']);
  confirm = true;
  await roles.enter('user');
  assert.deepEqual(actions, ['start', 'stop']);
  t.mock.method(rider, 'request', async () => ({ auditStatus: 'APPROVED' }));
  await roles.enter('rider');
  assert.equal(actions.at(-1), 'start');
  assert.deepEqual(routes, ['/pages/home/index', '/rider/pages/home/index']);
});

test('直达商户订单页时先检查审核，再决定是否执行原页面生命周期', async t => {
  const { roles, routes } = setup(t);
  let auditStatus = 'PENDING_REVIEW';
  t.mock.method(merchant, 'profile', async () => ({ store: { auditStatus } }));
  const { register } = require('../merchant/utils/page');
  let definition;
  global.Page = value => { definition = value; };
  t.after(() => { delete global.Page; });
  const events = [];
  register({ onLoad: options => events.push(options.id), onShow: () => events.push('show') });
  const page = { ...definition, data: { ...definition.data }, setData(data) { Object.assign(this.data, data); } };
  page.onLoad({ id: 'O1' });
  await page.onShow();
  assert.equal(page.data.roleReady, false);
  assert.deepEqual(events, []);
  assert.deepEqual(routes, ['/merchant/pages/onboarding/index']);
  auditStatus = 'APPROVED';
  await page.onShow();
  assert.equal(page.data.roleReady, true);
  assert.deepEqual(events, ['O1', 'show']);
  assert.equal(roles.activeRole(), 'merchant');
});
