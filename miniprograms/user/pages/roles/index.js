const roles = require('../../utils/roles');

Page({
  data: {
    current: 'user',
    pending: '',
    error: '',
    options: [
      { id: 'user', name: '用户端', description: '点餐、查看订单和管理收餐地址', icon: '/assets/v3-orders.svg' },
      { id: 'merchant', name: '商户端', description: '管理店铺、商品和接单', icon: '/assets/v3-roles.svg' },
      { id: 'rider', name: '骑手端', description: '领取餐品、查看配送和收益', icon: '/assets/v3-address.svg' }
    ]
  },

  onLoad({ from } = {}) {
    this.closed = false;
    if (roles.valid(from)) this.setData({ current: from });
  },

  onUnload() { this.closed = true; },

  async select(event) {
    const role = event.currentTarget.dataset.role;
    if (!roles.valid(role) || this.data.pending) return;
    if (role === this.data.current) return wx.navigateBack({ fail: () => this.enter(role) });
    return this.enter(role);
  },

  async enter(role) {
    this.setData({ pending: role, error: '' });
    try {
      await roles.enter(role, () => !this.closed);
    } catch (error) {
      if (!this.closed) this.setData({ error: error.message || '暂时无法切换角色，请稍后重试' });
    } finally {
      if (!this.closed) this.setData({ pending: '' });
    }
  }
});
