const api = require('../../utils/api');
const ui = require('../../utils/ui');
const labels = { settlements: '订单结算', debts: '欠款与扣回', ledger: '资金流水', withdrawals: '提现记录' };
const events = {
  debt_recover: '欠款扣回', debt_receive: '平台收回垫付款', withdraw_freeze: '提现冻结',
  withdraw_unfreeze: '提现解冻', withdraw_paid: '提现出款', settlement_credit: '订单结算入账'
};

Page({
  data: { rows: [], loading: true },
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
  order(event) { ui.go('/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); }
});
