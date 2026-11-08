const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { days: 1, periods: [{ days: 1, label: '今日' }, { days: 7, label: '近 7 天' }, { days: 30, label: '近 30 天' }], loading: true },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onboarding: ui.onboarding,
  async refresh() {
    if (this.inFlight) return;
    this.inFlight = true;
    this.setData({ loading: true, error: '', blocked: '' });
    try {
      const access = await ui.access();
      this.setData(access);
      if (access.blocked) return;
      const { sales, account, debtCents } = await api.request('/finance', { days: this.data.days });
      this.setData({
        sales: {
          count: sales.paidOrders, paid: ui.money(sales.paidCents), food: ui.money(sales.foodCents),
          refunded: ui.money(sales.refundedCents), net: ui.money(sales.netCents)
        },
        account: { available: ui.money(account.availableCents), frozen: ui.money(account.frozenCents), debt: ui.money(debtCents) }
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.inFlight = false;
      this.setData({ loading: false });
    }
  },
  period(event) {
    if (this.inFlight) return;
    this.setData({ days: Number(event.currentTarget.dataset.days) });
    this.refresh();
  },
  records(event) { ui.go('/pages/records/index?kind=' + event.currentTarget.dataset.kind); },
  withdraw() { ui.go('/pages/withdraw/index'); }
});
