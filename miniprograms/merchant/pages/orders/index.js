const api = require('../../utils/api');
const ui = require('../../utils/ui');

const filters = [
  { label: '全部', status: '' },
  { label: '待接单', status: '待商户接单' },
  { label: '出餐中', status: '商户出餐中' },
  { label: '待领取', status: '已到暂存点 / 待领取' },
  { label: '配送中', status: '配送中' },
  { label: '已完成', status: '已完成' },
  { label: '已关闭', status: '已关闭' }
];

Page({
  data: { filters, filter: 0, q: '', rows: [], counts: {}, loading: true, page: 0 },
  onShow() {
    const tabBar = this.getTabBar?.();
    if (tabBar) tabBar.setData({ selected: '/pages/orders/index' });
    this.refresh();
  },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { if (this.data.hasMore && !this.data.loading) this.load(this.data.page + 1); },
  onboarding: ui.onboarding,

  async refresh() {
    const run = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '', blocked: '' });
    try {
      const access = await ui.access();
      if (this.requestId !== run) return;
      this.setData(access);
      if (access.blocked) {
        this.setData({ loading: false, rows: [] });
      } else {
        await this.load(0);
      }
    } catch (error) {
      if (this.requestId === run) this.setData({ loading: false, error: error.message });
    }
  },

  async load(page) {
    const run = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '' });
    try {
      const data = await api.request('/orders', { page, status: filters[this.data.filter].status, q: this.data.q });
      if (this.requestId !== run) return;
      const rows = data.rows.map(order => ({
        ...order, amount: ui.money(order.paidCents), time: ui.date(order.paidAt), suffix: order.id.slice(-8),
        deadline: ui.date(order.acceptDeadlineAt), acceptExpired: Date.now() >= order.acceptDeadlineAt
      }));
      this.setData({ rows: page ? this.data.rows.concat(rows) : rows, counts: data.counts, page, hasMore: data.hasMore });
    } catch (error) {
      if (this.requestId === run) this.setData({ error: error.message });
    } finally {
      if (this.requestId === run) this.setData({ loading: false });
    }
  },

  search(event) { this.setData({ q: event.detail.value }); },
  submitSearch() { this.load(0); },
  filter(event) {
    this.setData({ filter: Number(event.currentTarget.dataset.index) });
    this.load(0);
  },
  details(event) { ui.go('/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); },
  aftersales() { ui.go('/pages/aftersales/index'); },

  update(event) {
    const { id, action } = event.currentTarget.dataset;
    ui.action(this, async () => {
      const accept = action === 'accept';
      if (!await ui.confirm(accept ? '确认接单' : '确认送达暂存点',
        accept ? '接单后请开始出餐。' : '确认餐品已经放置在暂存点，骑手可领取。')) return;
      await api.request(ui.orderPath(id) + '/status', { action }, 'POST');
      await this.load(0);
    });
  }
});
