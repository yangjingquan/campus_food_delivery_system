const api = require('../../utils/api');
Page({
  data: { order: null, type: 'partial', amount: '5.00', amountDisabled: false, paidText: '¥0.00', foodText: '¥0.00', refundedText: '¥0.00', remainingFoodText: '¥0.00', remainingPaidText: '¥0.00', canPartial: true, reasons: ['餐品有问题', '配送等待过久', '不想要了', '其他原因'], reasonIndex: 0, error: '' },
  async onLoad(query) { this.orderId = query.id; await this.load(); },
  async load() {
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}`);
      const beforeAcceptance = data.accepted !== true && data.fulfillStatus === '待商户接单';
      const remainingPaid = data.paidCents - data.refundedCents;
      const remainingFood = data.foodCents - data.foodRefundedCents;
      this.setData({ order: data, type: beforeAcceptance ? 'full' : 'partial', amount: beforeAcceptance ? (remainingPaid / 100).toFixed(2) : '5.00', amountDisabled: beforeAcceptance, canPartial: !beforeAcceptance, paidText: `¥${(data.paidCents / 100).toFixed(2)}`, foodText: `¥${(data.foodCents / 100).toFixed(2)}`, refundedText: `¥${(data.refundedCents / 100).toFixed(2)}`, remainingFoodText: `¥${(remainingFood / 100).toFixed(2)}`, remainingPaidText: `¥${(remainingPaid / 100).toFixed(2)}` });
    } catch (error) { wx.showToast({ title: error.message || '订单暂不可申请售后', icon: 'none' }); }
  },
  chooseType(event) {
    const type = event.currentTarget.dataset.value;
    const remaining = this.data.order.paidCents - this.data.order.refundedCents;
    this.setData({ type, amountDisabled: type === 'full', amount: type === 'full' ? (remaining / 100).toFixed(2) : '5.00', error: '' });
  },
  inputAmount(event) { this.setData({ amount: event.detail.value, error: '' }); },
  chooseReason(event) { this.setData({ reasonIndex: Number(event.detail.value) }); },
  inputDescription(event) { this.description = event.detail.value; },
  async submit() {
    const amount = String(this.data.amount).trim();
    if (!/^\d{1,8}(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) return this.setData({ error: '请输入有效的退款金额，最多两位小数。' });
    try {
      const response = await api.post(`/orders/${this.orderId}/aftersales`, { type: this.data.type, amountCents: Math.round(Number(amount) * 100), reason: this.data.reasons[this.data.reasonIndex], description: this.description || '' });
      wx.redirectTo({ url: `/pages/aftersale/index?id=${response.data.id}` });
    } catch (error) { this.setData({ error: error.message || '申请失败，请核对金额和申请时限。' }); }
  }
});
