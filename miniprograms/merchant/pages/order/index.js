const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true },
  onLoad(options) { this.id = options.id; },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },

  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const order = await api.request(ui.orderPath(this.id));
      const remaining = order.paidCents - order.refundedCents;
      const foodRemaining = order.foodCents - order.foodRefundedCents;
      this.setData({
        order, remaining, foodRemaining,
        amounts: {
          paid: ui.money(order.paidCents), delivery: ui.money(order.deliveryCents),
          food: ui.money(order.foodCents), refunded: ui.money(order.refundedCents),
          remaining: ui.money(remaining), foodRemaining: ui.money(foodRemaining)
        },
        paidAt: ui.date(order.paidAt),
        arrivedAt: order.arrivedAt ? ui.date(order.arrivedAt) : '',
        deadline: ui.date(order.acceptDeadlineAt), acceptExpired: Date.now() >= order.acceptDeadlineAt,
        lines: order.lines.map(item => ({ ...item, amount: ui.money(item.amountCents) })),
        aftersales: order.aftersales.map(item => ({
          ...item, amount: ui.money(item.amountCents), time: ui.date(item.createdAt),
          offlineTime: item.offlineActualAt ? ui.date(item.offlineActualAt) : ''
        })),
        events: order.events.map(item => ({ ...item, time: ui.date(item.createdAt) }))
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },

  status(event) {
    const { action } = event.currentTarget.dataset;
    ui.action(this, async () => {
      const accept = action === 'accept';
      if (!await ui.confirm(accept ? '确认接单' : '确认到点', accept ? '接单后请开始出餐。' : '确认餐品已送达暂存点。')) return;
      await api.request(ui.orderPath(this.id) + '/status', { action }, 'POST');
      await this.refresh();
    });
  },

  ticket() { ui.go('/pages/ticket/index?id=' + encodeURIComponent(this.id)); },
  map() { ui.go('/pages/map/index?id=' + encodeURIComponent(this.id)); },
  phone() { wx.makePhoneCall({ phoneNumber: this.data.order.delivery.phone }); },

  startRefund(event) {
    const { action, id } = event.currentTarget.dataset;
    const refund = this.data.aftersales.find(item => item.id === id);
    this.setData({
      refundForm: {
        action, refundId: id || '', type: refund ? refund.type : 'full',
        amount: refund && refund.type === 'partial' ? (refund.amountCents / 100).toFixed(2) : '',
        reason: '', restoreStock: false, requestKey: 'rf_' + Date.now() + '_' + Math.random().toString(36).slice(2)
      },
      formError: ''
    }, () => wx.pageScrollTo({ selector: '#refund-form', duration: 0 }));
  },
  cancelRefund() { if (!this.data.saving) this.setData({ refundForm: null, formError: '' }); },
  input(event) { this.setData({ ['refundForm.' + event.currentTarget.dataset.field]: event.detail.value }); },
  type(event) { this.setData({ 'refundForm.type': Number(event.detail.value) ? 'partial' : 'full' }); },
  restore(event) { this.setData({ 'refundForm.restoreStock': event.detail.value }); },
  submitRefund() {
    ui.action(this, async () => {
      const { action, refundId, type, amount, reason, restoreStock, requestKey } = this.data.refundForm;
      const body = { action, refundId, requestKey, reason: reason.trim() };
      if (['reject', 'negotiate'].includes(action) && !body.reason) throw new Error('请填写处理原因');
      const submitsMoney = ['approve', 'negotiate'].includes(action);
      if (submitsMoney) {
        body.type = type;
        body.amountCents = type === 'full' ? this.data.remaining : ui.cents(amount);
        body.restoreStock = restoreStock;
        if (body.amountCents <= 0 || body.amountCents > this.data.remaining ||
            (type === 'partial' && body.amountCents > this.data.foodRemaining)) {
          throw new Error('退款金额超出可退范围');
        }
      }
      const title = { approve: '同意退款', negotiate: '提交协商退款', reject: '拒绝退款', end: '结束售后' }[action];
      if (!await ui.confirm(title, submitsMoney ? '确认提交 ' + ui.money(body.amountCents) + ' 的退款。以渠道结果为准。' : '确认提交本次售后处理结果。')) return;
      await api.request(ui.orderPath(this.id) + '/refund', body, 'POST');
      this.setData({ refundForm: null });
      await this.refresh();
    });
  }
});
