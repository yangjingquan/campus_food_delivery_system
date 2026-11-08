const api = require('../../utils/api');
Page({
  data: { order: null, items: [], amountText: '', foodText: '', feeText: '', steps: ['待商户接单', '商户出餐中', '已到暂存点 / 待领取', '配送中', '已完成'], stepIndex: 0, latestAfterSale: null, canRefund: false, locationAvailable: false },
  async onLoad(query) { this.orderId = query.id; await this.load(); },
  async onShow() { if (this.orderId) await this.load(); },
  async load() {
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}`);
      const items = (data.items || []).map(item => ({ ...item, priceText: `¥${(item.priceCents / 100).toFixed(2)}`, amountText: `¥${(item.amountCents / 100).toFixed(2)}` }));
      const stepIndex = Math.max(0, ['待商户接单', '商户出餐中', '已到暂存点 / 待领取', '配送中', '已完成'].indexOf(data.fulfillStatus));
      const latestAfterSale = (data.aftersales || [])[0] || null;
      this.setData({ order: { ...data, refundedText: `¥${(data.refundedCents / 100).toFixed(2)}` }, items, stepIndex, latestAfterSale: latestAfterSale ? { ...latestAfterSale, refundedText: `¥${(data.refundedCents / 100).toFixed(2)}` } : null, amountText: `¥${(data.paidCents / 100).toFixed(2)}`, foodText: `¥${(data.foodCents / 100).toFixed(2)}`, feeText: `¥${(data.deliveryFeeCents / 100).toFixed(2)}`, locationAvailable: data.fulfillStatus === '配送中', canRefund: data.paymentStatus === '支付成功' && !['已关闭'].includes(data.fulfillStatus) });
    } catch (error) { wx.showToast({ title: error.message || '订单暂不可用', icon: 'none' }); }
  },
  map() { wx.navigateTo({ url: `/pages/map/index?id=${this.orderId}` }); },
  refund() { wx.navigateTo({ url: `/pages/refund/index?id=${this.orderId}` }); },
  afterSale(event) { wx.navigateTo({ url: `/pages/aftersale/index?id=${event.currentTarget.dataset.id}` }); },
  copy(event) { wx.setClipboardData({ data: event.currentTarget.dataset.value }); },
  addressList() { wx.navigateTo({ url: '/pages/addresses/index' }); }
});
