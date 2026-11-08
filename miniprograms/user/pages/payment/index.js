const api = require('../../utils/api');
Page({
  data: { payment: null, orders: [], amountText: '¥0.00', foodText: '¥0.00', feeText: '¥0.00', mode: 'disabled' },
  async onLoad(query) { this.paymentId = query.id; await this.load(); },
  async onShow() { if (this.paymentId) await this.load(); },
  async load() {
    try {
      const { data } = await api.get(`/payments/${this.paymentId}`);
      const orders = (data.merchantOrders || []).map(row => ({ ...row, amountText: `¥${(row.amountCents / 100).toFixed(2)}`, foodText: `¥${(row.foodCents / 100).toFixed(2)}`, feeText: `¥${(row.deliveryFeeCents / 100).toFixed(2)}` }));
      this.setData({ payment: data, orders, amountText: `¥${(data.amountCents / 100).toFixed(2)}`, foodText: `¥${((data.foodCents || 0) / 100).toFixed(2)}`, feeText: `¥${((data.deliveryFeeCents || 0) / 100).toFixed(2)}`, mode: data.mode || 'disabled' });
      wx.setNavigationBarTitle({ title: orders.length > 1 ? '合并支付单' : '订单支付' });
    } catch (error) { wx.showToast({ title: error.message || '无法读取支付单', icon: 'none' }); }
  },
  async pay() {
    if (!this.data.payment) return;
    if (this.data.mode === 'mock') {
      wx.showModal({ title: '本地模拟支付', content: '该按钮只在本地联调环境有效，不会扣款。', confirmText: '模拟成功', success: async result => { if (result.confirm) await this.finishMock(); } });
      return;
    }
    const params = this.data.payment.paymentParams;
    if (!params) return wx.showModal({ title: '微信支付未配置', content: '服务端还没有启用微信支付商户配置，订单会继续保持待付款。', showCancel: false });
    wx.requestPayment({ ...params, success: () => wx.redirectTo({ url: `/pages/payment-result/index?id=${this.paymentId}` }), fail: error => wx.showToast({ title: error.errMsg || '支付未完成', icon: 'none' }) });
  },
  async finishMock() {
    try { await api.post(`/payments/${this.paymentId}/mock-success`, {}); wx.redirectTo({ url: `/pages/payment-result/index?id=${this.paymentId}` }); }
    catch (error) { wx.showModal({ title: '支付尚未完成', content: error.message, showCancel: false }); }
  },
  backCart() { wx.navigateBack({ delta: 1 }); }
});
