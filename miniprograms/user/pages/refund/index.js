const api = require('../../utils/api');
const { money } = require('../../utils/display');
const { availability } = require('../../utils/aftersale');

const { showDialog, dialogEvents } = require('../../utils/dialog');

Page({
  ...dialogEvents,
  data: {
    order: null, type: 'partial', amount: '', reasons: ['餐品有问题', '配送等待过久', '不想要了', '其他原因'],
    reasonIndex: 0, error: '', loading: true, loadError: '', submitting: false
  },

  onLoad(query) { this.orderId = query.id; this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}`);
      const refund = availability(data);
      this.setData({
        order: data, refund, type: refund.canPartial ? 'partial' : 'full',
        amount: refund.canPartial ? '' : (refund.remaining / 100).toFixed(2),
        paidText: money(data.paidCents), foodText: money(data.foodCents), refundedText: money(data.refundedCents), feeText: money(data.deliveryFeeCents),
        remainingFoodText: money(refund.food), remainingPaidText: money(refund.remaining), loading: false
      });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  chooseType(event) {
    const type = event.currentTarget.dataset.value;
    const amount = type === 'full' ? (this.data.refund.remaining / 100).toFixed(2) : '';
    this.setData({ type, amount, error: '' });
  },

  inputAmount(event) { this.setData({ amount: event.detail.value, error: '' }); },
  chooseReason() {
    showDialog(this, {
      title: '申请原因', options: this.data.reasons, selected: this.data.reasonIndex, cancel: true
    }, reasonIndex => this.setData({ reasonIndex }));
  },
  inputDescription(event) { this.description = event.detail.value; },

  async submit() {
    const { refund, type, reasons, reasonIndex } = this.data;
    if (this.data.submitting || !refund?.allowed) return;
    const input = this.data.amount.trim();
    if (!/^\d{1,8}(\.\d{1,2})?$/.test(input)) return this.setData({ error: '请输入金额，最多两位小数。' });
    const [yuan, fraction = ''] = input.split('.');
    const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, '0'));
    const limit = type === 'full' ? refund.remaining : Math.min(refund.food, refund.remaining);
    if (cents <= 0 || cents > limit) return this.setData({ error: `请输入大于零且不超过 ${money(limit)} 的金额。` });

    this.setData({ submitting: true, error: '' });
    try {
      const { data } = await api.post(`/orders/${this.orderId}/aftersales`, {
        type, amountCents: cents, reason: reasons[reasonIndex], description: this.description || ''
      });
      wx.redirectTo({ url: `/pages/aftersale/index?id=${data.id}` });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ submitting: false });
    }
  }
});
