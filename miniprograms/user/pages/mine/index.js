const api = require('../../utils/api');
const { money } = require('../../utils/display');

Page({
  data: {
    user: null, config: {}, working: false, message: '',
    editing: false, avatarPath: '', draftAvatar: '', draftName: '', profileError: ''
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/mine/index');
    if (!this.data.editing && !this.data.working) this.refresh();
  },

  async refresh() {
    getApp().getConfig().then(config => this.setData({ config })).catch(() => {});
    const token = wx.getStorageSync('userToken');
    if (!token) {
      this.setData({ user: null, avatarPath: '' });
      return;
    }
    try {
      const { data } = await api.get('/me');
      if (wx.getStorageSync('userToken') === token) this.showUser(data);
    } catch (error) {
      if (wx.getStorageSync('userToken') !== token) return;
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.setData({ user: null, avatarPath: '' });
      } else {
        this.setData({ message: error.message });
      }
    }
  },

  showUser(user) {
    const displayName = user.displayName || '同学';
    const keepAvatar = user.hasAvatar && this.data.user?.id === user.id;
    getApp().globalData.user = user;
    this.setData({
      user: { ...user, displayName, initial: Array.from(displayName)[0] },
      avatarPath: keepAvatar ? this.data.avatarPath : '',
      message: ''
    });
    const token = wx.getStorageSync('userToken');
    if (user.hasAvatar && token) {
      const pending = api.downloadAvatar(token);
      this.avatarRequest = pending;
      pending.then(path => {
        if (this.avatarRequest === pending && wx.getStorageSync('userToken') === token) {
          this.setData({ avatarPath: path });
        }
      }).catch(() => {});
    }
  },

  async login() {
    if (this.data.working) return;
    this.setData({ working: true, message: '' });
    try {
      const user = await getApp().ensureLogin();
      this.showUser(user);
      if (user.hasAvatar === false && user.displayName === '同学') this.openProfile();
    } catch (error) {
      this.setData({ message: error.message || '微信登录暂不可用，请重试。' });
    } finally {
      this.setData({ working: false });
    }
  },

  openProfile() {
    this.setData({
      editing: true, draftName: this.data.user.displayName, draftAvatar: '', profileError: ''
    });
  },

  closeProfile() {
    this.setData({ editing: false, draftAvatar: '', profileError: '' });
  },

  chooseAvatar({ detail }) {
    if (detail.avatarUrl) this.setData({ draftAvatar: detail.avatarUrl, profileError: '' });
  },

  async saveProfile({ detail }) {
    if (this.data.working) return;
    const displayName = (detail.value.displayName || '').trim();
    if (!displayName || Array.from(displayName).length > 32) {
      this.setData({ profileError: '请输入 1–32 个字符的昵称' });
      return;
    }
    this.setData({ working: true, profileError: '' });
    try {
      const { data } = await api.updateProfile(displayName, this.data.draftAvatar);
      this.showUser(data);
      this.closeProfile();
      wx.showToast({ title: '资料已保存' });
    } catch (error) {
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.closeProfile();
        this.setData({ user: null, avatarPath: '', message: '登录已失效，请重新登录' });
      } else {
        this.setData({ profileError: error.message });
      }
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
    this.closeProfile();
    this.setData({ user: null, avatarPath: '' });
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
