const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { rows: [], loading: true },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { if (this.data.hasMore && !this.data.loading) this.load(this.data.page + 1); },
  refresh() { return this.load(0); },
  async load(page) {
    if (this.inFlight) return;
    this.inFlight = true;
    this.setData({ loading: true, error: '' });
    try {
      const data = await api.request('/aftersales', { page });
      const rows = data.map(item => ({ ...item, amount: ui.money(item.amountCents), time: ui.date(item.createdAt) }));
      this.setData({ rows: page ? this.data.rows.concat(rows) : rows, page, hasMore: rows.length === 30 });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.inFlight = false;
      this.setData({ loading: false });
    }
  },
  open(event) { ui.go('/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); }
});
