const api = require('../../utils/api');
Page({
  data: { user: null, config: {} },
  onShow() { this.refresh(); },
  async refresh() {
    try { const { data } = await api.get('/me'); this.setData({ user: { ...data, initial: (data.displayName || '同学').slice(0, 1) } }); }
    catch (_) { this.setData({ user: null }); }
    try { const { data } = await api.get('/config', { auth: false }); this.setData({ config: data }); } catch (_) {}
  },
  async login() {
    try { const user = await getApp().ensureLogin(); this.setData({ user: { ...user, initial: (user.displayName || '同学').slice(0, 1) } }); wx.showToast({ title: '登录成功', icon: 'success' }); }
    catch (error) { wx.showModal({ title: '登录暂不可用', content: error.message || '请稍后重试', showCancel: false }); }
  },
  async logout() {
    try { await api.post('/auth/logout', {}); } catch (_) {}
    wx.removeStorageSync('userToken'); this.setData({ user: null });
  },
  open(event) {
    const action = event.currentTarget.dataset.action;
    if (action === 'orders') wx.switchTab({ url: '/pages/orders/index' });
    else if (action === 'addresses') wx.navigateTo({ url: '/pages/addresses/index' });
    else if (action === 'rules') wx.showModal({ title: '配送规则与费用', content: `按商户数量收取固定配送费，当前每店 ¥${((this.data.config.deliveryFeeCents || 0) / 100).toFixed(2)}。配送时段 ${this.data.config.serviceStart || '以平台设置为准'}–${this.data.config.serviceEnd || ''}，预计时间会受出餐与配送影响。`, showCancel: false });
    else if (action === 'contact') wx.showToast({ title: '请通过平台客服渠道联系', icon: 'none' });
    else wx.showModal({ title: '关于寝食记', content: '校园外卖用户端 · 一期单校、单暂存点服务。仅使用订单必需信息。', showCancel: false });
  }
});
