const api = require('../../utils/api');
const { money } = require('../../utils/display');

const { showDialog, dialogEvents } = require('../../utils/dialog');

Page({
  ...dialogEvents,
  data: {
    user: null, config: {}, working: false, message: '',
    editingNickname: false, avatarPath: '', draftName: '', profileAction: ''
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/mine/index');
    if (!this.data.editingNickname && !this.data.working) this.refresh();
  },

  async refresh() {
    getApp().getConfig().then(config => this.setData({ config })).catch(() => {});
    const token = wx.getStorageSync('userToken');
    if (!token) {
      this.setData({ user: null, avatarPath: '' });
      return;
    }
    const pending = api.get('/me');
    this.profileRefresh = pending;
    try {
      const { data } = await pending;
      if (this.profileRefresh === pending && wx.getStorageSync('userToken') === token) this.showUser(data);
    } catch (error) {
      if (this.profileRefresh !== pending || wx.getStorageSync('userToken') !== token) return;
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.setData({ user: null, avatarPath: '' });
      } else {
        this.setData({ message: error.message });
      }
    }
  },

  showUser(user, selectedAvatar) {
    const displayName = user.displayName || '同学';
    const keepAvatar = user.hasAvatar && this.data.user?.id === user.id;
    getApp().globalData.user = user;
    this.setData({
      user: { ...user, displayName, initial: Array.from(displayName)[0] },
      avatarPath: selectedAvatar || (keepAvatar ? this.data.avatarPath : ''),
      message: ''
    });
    this.avatarRequest = null;
    const token = wx.getStorageSync('userToken');
    if (!selectedAvatar && user.hasAvatar && token) {
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
      this.showUser(await getApp().ensureLogin());
    } catch (error) {
      this.setData({ message: error.message || '微信登录暂不可用，请重试。' });
    } finally {
      this.setData({ working: false });
    }
  },

  editNickname() {
    if (this.data.working || !this.data.user) return;
    this.setData({
      editingNickname: true, draftName: this.data.user.displayName, message: ''
    });
  },

  chooseAvatar({ detail }) {
    if (this.data.working || !this.data.user) return;
    if (detail.avatarUrl) {
      return this.saveProfileChange(() => api.updateAvatar(detail.avatarUrl), '头像已更新', detail.avatarUrl);
    } else if (detail.errno === 112 || /not declared in the privacy agreement/i.test(detail.errMsg || '')) {
      this.setData({ message: '微信头像暂不可用，请稍后重试。' });
    }
  },

  saveNickname({ detail }) {
    if (this.data.working || !this.data.user || !this.data.editingNickname || detail.isSwitchEmoji) return;
    const displayName = (detail.value || '').trim();
    if (displayName === this.data.user.displayName) {
      this.setData({ editingNickname: false, message: '' });
      return;
    }
    if (!displayName || Array.from(displayName).length > 32) {
      this.setData({ message: '请输入 1–32 个字符的昵称' });
      return;
    }
    return this.saveProfileChange(() => api.put('/me', { displayName }), '昵称已更新');
  },

  async saveProfileChange(save, title, selectedAvatar) {
    const token = wx.getStorageSync('userToken');
    this.profileRefresh = null;
    this.setData({ working: true, profileAction: selectedAvatar ? 'avatar' : 'nickname', message: '' });
    try {
      const { data } = await save();
      if (wx.getStorageSync('userToken') !== token) return;
      this.showUser(data, selectedAvatar);
      if (!selectedAvatar) this.setData({ editingNickname: false });
      wx.showToast({ title });
    } catch (error) {
      if (wx.getStorageSync('userToken') !== token) return;
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.setData({ user: null, editingNickname: false, avatarPath: '', message: '登录已失效，请重新登录' });
      } else {
        this.setData({ message: error.message });
      }
    } finally {
      this.setData({ working: false, profileAction: '' });
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
    this.setData({ user: null, editingNickname: false, avatarPath: '', message: '' });
  },

  open(event) {
    const action = event.currentTarget.dataset.action;
    if (action === 'orders') wx.switchTab({ url: '/pages/orders/index' });
    if (action === 'addresses') wx.navigateTo({ url: '/pages/addresses/index' });
    if (action === 'rules') {
      const config = this.data.config;
      showDialog(this, {
        title: '配送规则与费用',
        content: config.serviceStart
          ? `每店配送费 ${money(config.deliveryFeeCents)}。配送时段 ${config.serviceStart}–${config.serviceEnd}，高峰每 ${config.peakBatchMinutes} 分钟一批。`
          : '暂时无法获取配送配置，请稍后重试。'
      });
    }
    if (action === 'privacy') {
      wx.openPrivacyContract({
        fail: () => wx.showToast({ title: '暂时无法打开隐私保护指引', icon: 'none' })
      });
    }
    if (action === 'about') {
      showDialog(this, { title: '关于寝食记', content: '寝食记为同学提供校园餐品与寝室配送服务。' });
    }
  }
});
