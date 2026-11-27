const api = require('../../utils/api');
const { deliveryFee } = require('../../utils/display');

const { showDialog, dialogEvents } = require('../../utils/dialog');

const DEFAULT_NICKNAME = '同学';
const NICKNAME_LENGTH_MESSAGE = '请输入 1–32 个字符的昵称';
const USER_NUMBER_TITLE = '用户编号';
const USER_NUMBER_TAP_WINDOW = 2000;
const USER_NUMBER_TAP_COUNT = 5;

Page({
  ...dialogEvents,
  data: {
    user: null, working: false, message: '',
    editingNickname: false, avatarPath: '', draftName: '', profileAction: '', loginProfile: null
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/mine/index');
    if (!this.data.loginProfile && !this.data.editingNickname && !this.data.working) this.refresh();
  },

  onHide() {
    this.userNumberTaps = null;
    if (this.data.dialog?.title === USER_NUMBER_TITLE) this.cancelDialog();
  },

  async refresh() {
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
    const displayName = user.displayName || DEFAULT_NICKNAME;
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

  tapUserNumberArea() {
    const { user, working, loginProfile, editingNickname, dialog } = this.data;
    if (user?.id == null || !wx.getStorageSync('userToken') || working || loginProfile || editingNickname || dialog) {
      this.userNumberTaps = null;
      return;
    }

    const now = Date.now();
    let taps = this.userNumberTaps;
    if (!taps || taps.userId !== user.id || now - taps.startedAt > USER_NUMBER_TAP_WINDOW) {
      taps = this.userNumberTaps = { userId: user.id, startedAt: now, count: 0 };
    }
    taps.count += 1;
    if (taps.count < USER_NUMBER_TAP_COUNT) return;

    this.userNumberTaps = null;
    const userNumber = String(user.id);
    showDialog(this, { title: USER_NUMBER_TITLE, content: userNumber, cancel: true, confirmText: '复制' }, () => {
      wx.setClipboardData({
        data: userNumber,
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
      });
    });
  },

  async login() {
    if (this.data.working) return;
    this.setData({ working: true, message: '' });
    try {
      const user = await getApp().ensureLogin();
      this.showUser(user);
      this.setData({ loginProfile: {
        avatarPath: '', displayName: user.displayName === DEFAULT_NICKNAME ? '' : (user.displayName || '')
      } });
    } catch (error) {
      this.setData({ message: error.message || '微信登录暂不可用，请重试。' });
    } finally {
      this.setData({ working: false });
    }
  },

  skipLoginProfile() {
    if (!this.data.working) this.setData({ loginProfile: null, message: '' });
  },

  saveLoginProfile({ detail }) {
    const { loginProfile, user, working } = this.data;
    if (!loginProfile || !user || working) return;
    const name = (detail.value.displayName || '').trim();
    if (Array.from(name).length > 32) {
      this.setData({ message: NICKNAME_LENGTH_MESSAGE });
      return;
    }
    const displayName = name && name !== user.displayName ? name : undefined;
    const { avatarPath } = loginProfile;
    if (!avatarPath && !displayName) return this.skipLoginProfile();
    return this.saveProfileChange(
      () => avatarPath ? api.updateAvatar(avatarPath, displayName) : api.put('/me', { displayName }),
      '资料已保存', avatarPath
    );
  },

  editNickname() {
    if (this.data.working || !this.data.user) return;
    this.setData({
      editingNickname: true, draftName: this.data.user.displayName, message: ''
    });
  },
  cancelNickname() {
    if (this.data.working) return;
    this.setData({ editingNickname: false, message: '' });
  },

  chooseAvatar({ detail }) {
    if (this.data.working || !this.data.user) return;
    if (detail.avatarUrl) {
      if (this.data.loginProfile) {
        this.setData({ 'loginProfile.avatarPath': detail.avatarUrl, message: '' });
        return;
      }
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
      this.setData({ message: NICKNAME_LENGTH_MESSAGE });
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
      if (this.data.loginProfile) this.setData({ loginProfile: null });
      if (!selectedAvatar) this.setData({ editingNickname: false });
      wx.showToast({ title });
    } catch (error) {
      if (wx.getStorageSync('userToken') !== token) return;
      if (error.statusCode === 401) {
        getApp().clearSession();
        this.setData({ user: null, loginProfile: null, editingNickname: false, avatarPath: '', message: '登录已失效，请重新登录' });
      } else {
        this.setData({ message: error.message });
      }
    } finally {
      this.setData({ working: false, profileAction: '' });
    }
  },

  logout() {
    if (this.data.working) return;
    showDialog(this, {
      title: '退出登录',
      content: '退出后需重新登录才能下单和查看本人订单。',
      cancel: true,
      destructive: true,
      confirmText: '退出登录'
    }, () => this.performLogout());
  },

  async performLogout() {
    if (this.data.working) return;
    this.userNumberTaps = null;
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

  async showDeliveryRules() {
    const title = '配送规则与费用';
    showDialog(this, { title, content: '正在获取配送配置…' });
    const request = this.deliveryRulesRequest = getApp().getConfig(true);
    let content = '暂时无法获取配送配置，请稍后重试。';
    try {
      const config = await request;
      const rule = config.deliveryFeeRule;
      if ((rule?.value ?? config.deliveryFeeCents) != null) {
        const fee = deliveryFee(rule, config.deliveryFeeCents);
        content = `平台默认按商户订单收取配送费，每单 ${fee}。商户单独定价以结算页为准。`;
      } else {
        content = '暂时无法获取配送费，请稍后重试。';
      }
      if (config.serviceStart && config.serviceEnd) {
        content += `配送时段 ${config.serviceStart}–${config.serviceEnd}`;
        content += config.peakBatchMinutes == null ? '。' : `，高峰每 ${config.peakBatchMinutes} 分钟一批。`;
      }
    } catch (_) {
      // 读取失败时显示提示，用户重新打开即可重试。
    }
    if (this.deliveryRulesRequest === request && this.data.dialog?.title === title) {
      this.setData({ 'dialog.content': content });
    }
  },

  open(event) {
    const action = event.currentTarget.dataset.action;
    if (action === 'roles') return require('../../utils/roles').openPicker('user');
    if (action === 'orders') wx.switchTab({ url: '/pages/orders/index' });
    if (action === 'addresses') wx.navigateTo({ url: '/pages/addresses/index' });
    if (action === 'rules') return this.showDeliveryRules();
    if (action === 'privacy') {
      wx.openPrivacyContract({
        fail: () => wx.showToast({ title: '暂时无法打开隐私保护指引', icon: 'none' })
      });
    }
    if (action === 'about') {
      const uidNote = this.data.user?.id != null ? `\n当前用户编号：${this.data.user.id}` : '';
      showDialog(this, { title: '关于寝食记', content: `寝食记为同学提供校园餐品与寝室配送服务。${uidNote}` });
    }
  }
});
