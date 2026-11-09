const api = require('../../utils/api');
const { money } = require('../../utils/display');

Page({
  data: { user: null, config: {}, working: false, message: '' },

  onShow() {
    this.getTabBar?.()?.refresh('pages/mine/index');
    this.refresh();
  },

  async refresh() {
    getApp().getConfig().then(config => this.setData({ config })).catch(() => {});
    if (!wx.getStorageSync('userToken')) return;
    try {
      const { data } = await api.get('/me');
      this.showUser(data);
    } catch (error) {
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.setData({ user: null });
      } else {
        this.setData({ message: error.message });
      }
    }
  },

  showUser(user) {
    const displayName = user.displayName || '同学';
    this.setData({ user: { ...user, displayName, initial: displayName.slice(0, 1) }, message: '' });
  },

  async login() {
    if (this.data.working) return;
    this.setData({ working: true, message: '' });
    try {
      this.showUser(await getApp().ensureLogin());
    } catch (error) {
      this.setData({ message: error.message || '微信登录暂不可用，请重试。' });
    } finally {
      this.setData({ working: false });
    }
  },

  async logout() {
    if (this.data.working) return;
    this.setData({ working: true, message: '' });
    try {
      await api.post('/auth/logout', {});
    } catch (error) {
      if (error.statusCode !== 401) {
        this.setData({ message: error.message });
        return;
      }
    } finally {
      this.setData({ working: false });
    }
    getApp().clearSession();
    this.setData({ user: null });
  },

  open(event) {
    const action = event.currentTarget.dataset.action;
    if (action === 'orders') wx.switchTab({ url: '/pages/orders/index' });
    if (action === 'addresses') wx.navigateTo({ url: '/pages/addresses/index' });
    if (action === 'rules') {
      const config = this.data.config;
      wx.showModal({
        title: '配送规则与费用',
        content: config.serviceStart
          ? `每店配送费 ${money(config.deliveryFeeCents)}。配送时段 ${config.serviceStart}–${config.serviceEnd}，高峰每 ${config.peakBatchMinutes} 分钟一批。`
          : '暂时无法获取配送配置，请稍后重试。',
        showCancel: false
      });
    }
    if (action === 'privacy') {
      wx.openPrivacyContract({
        fail: () => wx.showToast({ title: '暂时无法打开隐私保护指引', icon: 'none' })
      });
    }
    if (action === 'about') {
      wx.showModal({ title: '关于寝食记', content: '寝食记为同学提供校园餐品与寝室配送服务。', showCancel: false });
    }
  }
});
