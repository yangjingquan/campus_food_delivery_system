const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');

function mine({ user = { id: 8, displayName: '同学', hasAvatar: false }, loginError } = {}) {
  let page;
  let token;
  let now = 1000;
  const uploads = [];
  const requests = [];
  const clipboard = [];
  const wx = {
    getStorageSync: key => key === 'userToken' ? token : undefined,
    showToast() {},
    uploadFile: options => uploads.push(options),
    request: options => requests.push(options),
    downloadFile() {},
    setClipboardData: ({ data }) => clipboard.push(data)
  };
  const app = {
    globalData: {},
    async ensureLogin() {
      if (loginError) throw loginError;
      token = 'user-session';
      return user;
    }
  };
  const apiModule = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8'), { wx, module: apiModule });
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/mine/index.js'), 'utf8'), {
    wx, getApp: () => app, Date: { now: () => now },
    require: id => id.endsWith('/api') ? apiModule.exports : id.endsWith('/dialog') ? require('../utils/dialog') : {},
    Page: definition => { page = definition; }
  });
  page.setData = function (changes) {
    for (const [key, value] of Object.entries(changes)) {
      if (key === 'loginProfile.avatarPath') this.data.loginProfile.avatarPath = value;
      else this.data[key] = value;
    }
  };
  return { page, uploads, requests, clipboard, advance: milliseconds => { now += milliseconds; } };
}

const avatarPath = 'wxfile://wechat-avatar.png';
const chooseAvatar = page => page.chooseAvatar({ detail: { avatarUrl: avatarPath } });
const submit = (page, displayName) => page.saveLoginProfile({ detail: { value: { displayName } } });

test('微信登录后弹出资料面板，确认后在同一次上传中保存头像和微信昵称', async () => {
  const { page, uploads, requests } = mine();
  await page.login();
  assert.equal(page.data.loginProfile.displayName, '');
  chooseAvatar(page);
  assert.equal(uploads.length, 0);

  const saving = submit(page, ' 杨小胖 ');
  assert.equal(uploads.length, 1);
  assert.equal(requests.length, 0);
  assert.equal(uploads[0].filePath, avatarPath);
  assert.equal(uploads[0].formData.displayName, '杨小胖');
  assert.equal(uploads[0].header.Authorization, 'Bearer user-session');
  uploads[0].success({ statusCode: 200, data: '{"id":8,"displayName":"杨小胖","hasAvatar":true}' });
  await saving;

  assert.equal(page.data.loginProfile, null);
  assert.equal(page.data.user.displayName, '杨小胖');
  assert.equal(page.data.avatarPath, avatarPath);
});

test('登录失败时保留登录提示，跳过资料时保留已登录账号', async () => {
  const failed = mine({ loginError: new Error('登录失败') });
  await failed.page.login();
  assert.equal(failed.page.data.loginProfile, null);
  assert.equal(failed.page.data.message, '登录失败');

  const { page, uploads, requests } = mine();
  await page.login();
  chooseAvatar(page);
  page.skipLoginProfile();
  assert.equal(page.data.loginProfile, null);
  assert.equal(page.data.user.id, 8);
  assert.equal(uploads.length + requests.length, 0);
});

test('单独选择昵称时只更新昵称，原头像保留', async () => {
  const { page, uploads, requests } = mine({ user: { id: 8, displayName: '旧昵称', hasAvatar: true } });
  await page.login();
  assert.equal(page.data.loginProfile.displayName, '旧昵称');

  const saving = submit(page, '新昵称');
  assert.equal(uploads.length, 0);
  assert.equal(requests[0].method, 'PUT');
  assert.equal(requests[0].data.displayName, '新昵称');
  requests[0].success({ statusCode: 200, data: { id: 8, displayName: '新昵称', hasAvatar: true } });
  await saving;
  assert.equal(page.data.user.hasAvatar, true);
});

test('资料保存失败后可以重试，未修改昵称时仅上传头像', async () => {
  const { page, uploads } = mine({ user: { id: 8, displayName: '旧昵称', hasAvatar: true } });
  await page.login();
  chooseAvatar(page);
  const firstSave = submit(page, '旧昵称');
  assert.equal(uploads[0].formData, undefined);
  uploads[0].fail();
  await firstSave;
  assert.equal(page.data.loginProfile.avatarPath, avatarPath);
  assert.match(page.data.message, /头像上传失败/);

  const retry = submit(page, '旧昵称');
  uploads[1].success({ statusCode: 200, data: '{"id":8,"displayName":"旧昵称","hasAvatar":true}' });
  await retry;
  assert.equal(page.data.loginProfile, null);
  assert.equal(page.data.user.displayName, '旧昵称');
});

test('两秒内第五次点击显示用户编号，确认后复制编号', async () => {
  const { page, clipboard, advance } = mine();
  await page.login();
  page.skipLoginProfile();
  for (let i = 0; i < 4; i++) page.tapUserNumberArea();
  assert.equal(page.data.dialog, undefined);

  advance(2000);
  page.tapUserNumberArea();
  assert.equal(page.data.dialog.title, '用户编号');
  assert.equal(page.data.dialog.content, '8');
  assert.equal(page.data.dialog.confirmText, '复制');
  assert.equal(clipboard.length, 0);
  page.confirmDialog();
  assert.deepEqual(clipboard, ['8']);
  assert.equal(page.data.dialog, null);
});

test('游客和资料弹窗打开时不计入用户编号手势', async () => {
  const { page } = mine();
  for (let i = 0; i < 5; i++) page.tapUserNumberArea();
  assert.equal(page.data.dialog, undefined);

  await page.login();
  for (let i = 0; i < 5; i++) page.tapUserNumberArea();
  page.skipLoginProfile();
  page.tapUserNumberArea();
  assert.equal(page.data.dialog, undefined);
});

test('点击超时和离开页面都会清除用户编号的连续点击进度', async () => {
  const { page, advance } = mine();
  await page.login();
  page.skipLoginProfile();
  for (let i = 0; i < 4; i++) page.tapUserNumberArea();
  advance(2001);
  page.tapUserNumberArea();
  assert.equal(page.data.dialog, undefined);

  for (let i = 0; i < 3; i++) page.tapUserNumberArea();
  page.onHide();
  for (let i = 0; i < 4; i++) page.tapUserNumberArea();
  assert.equal(page.data.dialog, undefined);
  page.tapUserNumberArea();
  page.onHide();
  assert.equal(page.data.dialog, null);
});
