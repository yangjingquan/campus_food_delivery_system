const merchant = require('./merchant/api');
const rider = require('./rider/api');

const home = '/pages/home/index';
const picker = '/pages/roles/index';
const storageKey = 'last-workspace-role';
const recentScenes = [1001, 1027, 1089, 1090, 1103, 1189];
const workspaces = {
  user: home,
  merchant: '/merchant/pages/orders/index',
  rider: '/rider/pages/home/index'
};

let active = 'user';
let foreground = true;
let riderLocation;

function valid(role) {
  return Object.prototype.hasOwnProperty.call(workspaces, role);
}

function rememberedRole(options = {}) {
  const path = options.path?.replace(/^\//, '');
  if ((path && path !== home.slice(1)) || Object.keys(options.query || {}).length) return 'user';
  if (options.scene && !recentScenes.includes(options.scene) && options.scene !== 1000) return 'user';
  const saved = wx.getStorageSync(storageKey);
  return valid(saved) ? saved : 'user';
}

async function status(role) {
  if (role === 'merchant') {
    const { store } = await merchant.profile();
    return store?.auditStatus || 'NOT_ENROLLED';
  }
  if (role === 'rider') {
    const profile = await rider.request('/profile');
    return profile.auditStatus;
  }
  return 'APPROVED';
}

function destination(role, audit) {
  if (role === 'user' || audit === 'APPROVED') return workspaces[role];
  if (role === 'merchant') return '/merchant/pages/onboarding/index';
  return audit === 'NOT_ENROLLED' ? '/rider/pages/join/index' : '/rider/pages/audit/index';
}

function activate(role, remember = true) {
  if (active === 'rider' && role !== 'rider') riderLocation?.stop();
  active = role;
  if (remember && wx.getStorageSync(storageKey) !== role) wx.setStorageSync(storageKey, role);
  if (role === 'rider' && foreground) riderLocation?.start();
}

function attachRiderLocation(location) {
  riderLocation = location;
}

function onShow(options = {}) {
  foreground = true;
  const path = options.path?.replace(/^\//, '');
  const publicEntry = path?.startsWith('pages/') && path !== picker.slice(1);
  if (active === 'rider' && (path?.startsWith('merchant/') ||
      (publicEntry && (path !== home.slice(1) || !recentScenes.includes(options.scene))))) {
    activate('user', false);
  }
  if (active === 'rider') riderLocation?.start();
}

function onHide() {
  foreground = false;
  riderLocation?.stop();
}

function navigate(method, url) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const pages = getCurrentPages();
      if (pages[pages.length - 1]?.route === url.slice(1)) resolve();
      else reject(new Error('页面打开超时，请重试'));
    }, 10000);
    const options = {
      url,
      success() { clearTimeout(timer); resolve(); },
      fail() { clearTimeout(timer); reject(new Error('页面打开失败，请重试')); }
    };
    if (method === 'reLaunch') wx.reLaunch(options);
    else wx.redirectTo(options);
  });
}

function openPicker(role = active) {
  wx.navigateTo({
    url: picker + '?from=' + role,
    fail: () => wx.showToast({ title: '无法打开角色列表，请重试', icon: 'none' })
  });
}

async function confirmLeavingRider() {
  if (active !== 'rider' || !riderLocation || !(await riderLocation.hasActiveDelivery())) return true;
  return new Promise(resolve => wx.showModal({
    title: '配送定位将暂停',
    content: '切换角色后将暂停共享配送位置，切回骑手端后恢复。',
    confirmText: '继续切换',
    confirmColor: '#147b58',
    success: result => resolve(result.confirm),
    fail: () => resolve(false)
  }));
}

async function enter(role, stillHere = () => true) {
  if (!valid(role)) return false;
  if (role !== 'rider' && !(await confirmLeavingRider())) return false;
  if (!stillHere()) return false;
  const audit = await status(role);
  if (!stillHere()) return false;
  const previous = active;
  activate(audit === 'APPROVED' ? role : 'user', false);
  try {
    await navigate('reLaunch', destination(role, audit));
  } catch (error) {
    activate(previous, false);
    throw error;
  }
  if (audit === 'APPROVED') activate(role);
  return true;
}

async function showVerification(role, audit) {
  if (active === role) activate('user', false);
  await navigate('redirectTo', destination(role, audit));
}

function forget(role) {
  if (wx.getStorageSync(storageKey) === role) wx.removeStorageSync(storageKey);
  if (active === role) activate('user', false);
}

module.exports = {
  activeRole: () => active,
  activate,
  attachRiderLocation,
  enter,
  forget,
  onHide,
  onShow,
  openPicker,
  rememberedRole,
  showVerification,
  status,
  valid
};
