const api = require('../../utils/api');
const ui = require('../../utils/ui');
const labels = { settlements: '订单结算', debts: '欠款与扣回', ledger: '资金流水', withdrawals: '提现记录' };
const events = {
  debt_recover: '欠款扣回', debt_receive: '平台收回垫付款', withdraw_freeze: '提现冻结',
  withdraw_unfreeze: '提现解冻', withdraw_paid: '提现出款', settlement_credit: '订单结算入账'
};

Page({
  data: { rows: [], loading: true, confirmingId: '' },
  onLoad(options) {
    this.kind = labels[options.kind] ? options.kind : 'withdrawals';
    this.setData({ kind: this.kind, title: labels[this.kind] });
    wx.setNavigationBarTitle({ title: labels[this.kind] });
  },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const rows = (await api.request('/finance/' + this.kind)).map(item => ({
        ...item,
        time: this.kind === 'withdrawals' ? item.createdAt : ui.date(item.createdAt || item.completedAt),
        label: events[item.eventType] || item.eventType,
        amount: ui.money(item.amountCents), food: ui.money(item.foodCents),
        refunded: ui.money(item.foodRefundedCents),
        net: item.settledCents == null ? '' : ui.money(item.settledCents),
        netTime: ui.date(item.settledAt),
        riderAmount: ui.money(item.riderDueCents),
        advance: ui.money(item.advanceCents), recovered: ui.money(item.recoveredCents),
        debt: ui.money(item.advanceCents - item.recoveredCents),
        available: ui.money(item.availableDelta), frozen: ui.money(item.frozenDelta)
      }));
      this.setData({ rows });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  async confirmWithdrawal(event) {
    const { id } = event.currentTarget.dataset;
    if (this.data.confirmingId) return;
    if (!wx.canIUse('requestMerchantTransfer')) {
      wx.showToast({ title: '请更新微信后确认收款', icon: 'none' });
      return;
    }
    this.setData({ confirmingId: id });
    try {
      const parameters = await api.request('/finance/withdrawals/' + encodeURIComponent(id) + '/confirmation');
      await new Promise((resolve, reject) => wx.requestMerchantTransfer({
        ...parameters, success: resolve,
        fail: () => reject(new Error('未完成收款确认，请刷新查看')),
      }));
      wx.showToast({ title: '已确认，等待微信结果', icon: 'none' });
    } catch (error) {
      wx.showToast({ title: error.message, icon: 'none' });
    } finally {
      this.setData({ confirmingId: '' });
      await this.refresh();
    }
  },
  order(event) { ui.go('/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); }
});
