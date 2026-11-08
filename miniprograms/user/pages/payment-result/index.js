const api = require('../../utils/api');
Page({
  data: { payment: null, orders: [], amountText: '¥0.00' },
  async onLoad(query) { this.paymentId = query.id; await this.load(); },
  async load() {
    try {
      const { data } = await api.get(`/payments/${this.paymentId}`);
      const orders = (data.merchantOrders || []).map(item => ({ ...item, amountText: `¥${(item.amountCents / 100).toFixed(2)}` }));
      this.setData({ payment: data, orders, amountText: `¥${(data.amountCents / 100).toFixed(2)}` });
      if (data.status === '支付成功') wx.removeStorageSync('shoppingCart');
    } catch (error) { wx.showToast({ title: error.message || '支付结果暂不可用', icon: 'none' }); }
  },
  openOrder(event) { wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` }); },
  orders() { wx.switchTab({ url: '/pages/orders/index' }); }
});
