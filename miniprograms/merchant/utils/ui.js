const api = require('./api');

const money = cents => '¥' + (Number(cents || 0) / 100).toFixed(2);
const orderPath = id => '/orders/' + encodeURIComponent(id);

function date(value) {
  if (!value) return '—';
  const raw = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(raw)) return String(value);
  return new Date(raw + 28800000).toISOString().slice(0, 16).replace('T', ' ');
}

function cents(value) {
  const text = String(value || '').trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(text)) throw new Error('请输入最多两位小数的正金额');
  const [whole, fraction = ''] = text.split('.');
  const amount = Number(whole) * 100 + Number((fraction + '00').slice(0, 2));
  if (amount <= 0) throw new Error('金额须大于 0');
  return amount;
}

async function access() {
  if (!api.token()) return { blocked: '请先使用微信登录' };
  try {
    const profile = await api.request('/profile');
    const store = profile.store;
    return { store, blocked: store && store.auditStatus === '已通过' ? '' : '店铺通过审核后即可使用工作台' };
  } catch (error) {
    if (error.status === 401) return { blocked: '登录已过期，请重新登录' };
    throw error;
  }
}

function confirm(title, content) {
  return new Promise(resolve => wx.showModal({
    title, content, confirmColor: '#167953',
    success: result => resolve(result.confirm), fail: () => resolve(false)
  }));
}

function go(url) {
  wx.navigateTo({ url });
}

function onboarding() {
  go('/pages/onboarding/index');
}

async function action(page, work) {
  if (page.data.saving) return;
  page.setData({ saving: true, formError: '' });
  try {
    await work();
  } catch (error) {
    page.setData({ formError: error.message });
  } finally {
    page.setData({ saving: false });
  }
}

module.exports = { money, date, cents, access, confirm, go, onboarding, action, orderPath };
