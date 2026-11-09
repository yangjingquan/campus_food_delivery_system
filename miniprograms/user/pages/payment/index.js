const api = require('../../utils/api');
const { money } = require('../../utils/display');

Page({
  data: { payment: null, orders: [], loading: true, loadError: '', paying: false, remaining: '', expired: false },

  onLoad(query) { this.paymentId = query.id; },
  onShow() {
    this.load();
    this.timer = setInterval(() => this.updateCountdown(), 1000);
  },
  onHide() { clearInterval(this.timer); },
  onUnload() { clearInterval(this.timer); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/payments/${this.paymentId}`);
      const orders = (data.merchantOrders || []).map(row => ({
        ...row, amountText: money(row.amountCents), foodText: money(row.foodCents), feeText: money(row.deliveryFeeCents)
      }));
      this.setData({
        payment: data, orders, amountText: money(data.amountCents), foodText: money(data.foodCents),
        feeText: money(data.deliveryFeeCents), loading: false
      });
      wx.setNavigationBarTitle({ title: orders.length > 1 ? '合并支付单' : '订单支付' });
      this.updateCountdown();
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  updateCountdown() {
    if (!this.data.payment) return;
    const seconds = Math.max(0, Math.ceil((this.data.payment.expiresAt - Date.now()) / 1000));
    const remaining = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    if (this.data.remaining !== remaining) this.setData({ remaining, expired: seconds === 0 });
  },

  async pay() {
    const { payment, paying, expired } = this.data;
    if (!payment || payment.status !== '未支付' || paying || expired) return;
    this.setData({ paying: true, payError: '' });
    try {
      if (payment.mode === 'mock') {
        await api.post(`/payments/${this.paymentId}/mock-success`, {});
      } else {
        const { data } = await api.post(`/payments/${this.paymentId}/prepare`, {});
        await new Promise((resolve, reject) => wx.requestPayment({ ...data, success: resolve, fail: reject }));
      }
      this.result();
    } catch (error) {
      if (!String(error.errMsg || '').includes('cancel')) {
        this.setData({ payError: error.message || '付款结果暂未确认，请查询支付结果后再试。' });
      }
    } finally {
      this.setData({ paying: false });
    }
  },

  result() { wx.redirectTo({ url: `/pages/payment-result/index?id=${this.paymentId}` }); },
  backCart() { wx.switchTab({ url: '/pages/orders/index' }); }
});
