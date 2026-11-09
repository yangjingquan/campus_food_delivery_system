const api = require('../../utils/api');
const cart = require('../../utils/cart');
const { money } = require('../../utils/display');

Page({
  data: { payment: null, orders: [], amountText: '¥0.00', loading: true, loadError: '', syncError: '' },

  onLoad(query) { this.paymentId = query.id; },
  onShow() { this.visible = true; this.load(); },
  onHide() { this.visible = false; clearTimeout(this.retry); },
  onUnload() { this.onHide(); },

  async load() {
    clearTimeout(this.retry);
    if (this.refreshing) return;
    this.refreshing = true;
    this.setData({ loading: !this.data.payment, loadError: '' });
    try {
      const user = await getApp().ensureLogin();
      const { data: saved } = await api.get(`/payments/${this.paymentId}`);
      let payment = saved;
      let syncError = '';
      if (saved.mode === 'wechat' && saved.status === '未支付') {
        try {
          const { data } = await api.post(`/payments/${this.paymentId}/sync`, {});
          payment = data;
        } catch (_) {
          syncError = '暂时无法核对微信付款结果，请稍后刷新。';
        }
      }
      const orders = (payment.merchantOrders || []).map(item => ({ ...item, amountText: money(item.amountCents) }));
      this.setData({ payment, orders, amountText: money(payment.amountCents), syncError });
      if (payment.status === '支付成功') this.removePaidItems(user.id);
      if (this.visible && payment.mode === 'wechat' && payment.status === '未支付' && (this.checks || 0) < 3) {
        this.checks = (this.checks || 0) + 1;
        this.retry = setTimeout(() => this.load(), 3000);
      }
    } catch (error) {
      this.setData({ loadError: error.message });
    } finally {
      this.refreshing = false;
      this.setData({ loading: false });
    }
  },

  removePaidItems(userId) {
    const key = 'cartPayment:' + this.paymentId;
    const snapshot = wx.getStorageSync(key);
    if (snapshot?.userId !== userId) return;
    // 只减去该笔支付对应的数量，保留付款前后新加入的餐品。
    const paid = new Map(snapshot.items.map(item => [item.productId, item]));
    const remaining = cart.read().map(item => {
      const purchased = paid.get(item.productId);
      if (!purchased || (purchased.lineKey || '') !== (item.lineKey || '')) return item;
      return { ...item, quantity: Math.max(0, item.quantity - purchased.quantity) };
    });
    cart.save(remaining.filter(item => item.quantity > 0));
    wx.removeStorageSync(key);
    if (wx.getStorageSync('checkoutAttempt')?.paymentId === this.paymentId) {
      wx.removeStorageSync('checkoutAttempt');
    }
  },

  openOrder(event) {
    wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` });
  },
  openPayment() { wx.redirectTo({ url: `/pages/payment/index?id=${this.paymentId}` }); },
  orders() { wx.switchTab({ url: '/pages/orders/index' }); }
});
