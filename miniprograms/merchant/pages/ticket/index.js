const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true },
  onLoad(options) { this.id = options.id; this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const ticket = await api.request(ui.orderPath(this.id) + '/ticket', {}, 'POST');
      const { store } = await api.request('/profile');
      this.setData({
        ticket, store, paid: ui.money(ticket.paidCents), fee: ui.money(ticket.deliveryCents),
        lines: ticket.lines.map(item => ({ ...item, amount: ui.money(item.amountCents) }))
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  copy() {
    const { ticket, store, fee, paid, lines } = this.data;
    const items = lines.map(item => item.name + ' × ' + item.quantity + ' ' + item.amount);
    wx.setClipboardData({ data: [store.name, ticket.id, ...items, '配送费 ' + fee, '实付 ' + paid, '领取码 ' + ticket.code].join('\n') });
  }
});
