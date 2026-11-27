const { register } = require('../../utils/page');
const api = require('../../../utils/merchant/api');
const ui = require('../../utils/ui');

const filters = [
  { label: '全部', status: '' },
  { label: '待接单', status: 'AWAITING_MERCHANT' },
  { label: '出餐中', status: 'PREPARING_FOOD' },
  { label: '待领取', status: 'AWAITING_RIDER' },
  { label: '待自取', status: 'AWAITING_PICKUP' },
  { label: '配送中', status: 'DELIVERING' },
  { label: '已完成', status: 'COMPLETED' },
  { label: '已关闭', status: 'CLOSED' }
];

const tabMeta = {
  orders: { title: '订单工作台', path: '/merchant/pages/orders/index' },
  products: { title: '商品管理', path: '/merchant/pages/products/index' },
  finance: { title: '经营数据', path: '/merchant/pages/finance/index' },
  mine: { title: '我的店铺', path: '/merchant/pages/mine/index' }
};

register({
  data: {
    activeTab: 'orders',
    selectedTabPath: '/merchant/pages/orders/index',
    productOrderChanged: false,
    filters, filter: 0, q: '', rows: [], counts: {}, loading: true, page: 0, hasMore: false, moreError: ''
  },
  onLoad(options) {
    const tab = options?.tab;
    if (tab && tabMeta[tab]) {
      this.setData({ activeTab: tab, selectedTabPath: tabMeta[tab].path });
      if (typeof wx !== 'undefined') wx.setNavigationBarTitle?.({ title: tabMeta[tab].title });
    }
  },
  onShow() {
    if (!this.data.activeTab || this.data.activeTab === 'orders') {
      this.refresh();
    }
  },
  onPullDownRefresh() {
    const tab = this.data.activeTab || 'orders';
    let work;
    if (tab === 'orders') work = this.refresh();
    else {
      const pane = this.selectComponent?.(`#${tab}-pane`);
      work = Promise.resolve(pane?.refresh?.());
    }
    return Promise.resolve(work).finally(() => {
      if (typeof wx !== 'undefined') wx.stopPullDownRefresh?.();
    });
  },
  onReachBottom() {
    if ((!this.data.activeTab || this.data.activeTab === 'orders') && !this.data.moreError) return this.loadMore();
  },
  onboarding: ui.onboarding,
  switchMerchantTab(event) {
    const key = event?.detail?.key;
    if (!key || !tabMeta[key] || key === this.data.activeTab) return;
    this.setData({ activeTab: key, selectedTabPath: tabMeta[key].path });
    if (typeof wx !== 'undefined') {
      wx.setNavigationBarTitle?.({ title: tabMeta[key].title });
      wx.pageScrollTo?.({ scrollTop: 0, duration: 0 });
    }
    if (key === 'orders') this.refresh();
  },
  onProductOrderChange(event) {
    this.setData({ productOrderChanged: !!event?.detail?.changed });
  },
  clearSearch() {
    this.setData({ q: '' });
    return this.load(0);
  },

  async refresh() {
    const run = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '', blocked: '', hasMore: false, moreError: '' });
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
    if (page === 0) {
      this.orderQuery = { status: filters[this.data.filter].status, q: this.data.q };
      this.setData({ page: 0, hasMore: false });
    }
    this.setData({ loading: true, error: '', moreError: '' });
    try {
      const data = await api.request('/orders', { ...this.orderQuery, page });
      if (this.requestId !== run) return;
      const rows = data.rows.map(order => ({
        ...order, amount: ui.money(order.paidCents), time: ui.date(order.paidAt), suffix: order.id.slice(-8),
        deadline: ui.date(order.acceptDeadlineAt), acceptExpired: Date.now() >= order.acceptDeadlineAt
      }));
      const previous = page > 0 ? this.data.rows : [];
      const seen = new Set(previous.map(order => order.id));
      this.setData({
        rows: previous.concat(rows.filter(order => !seen.has(order.id))),
        counts: data.counts, page, hasMore: data.hasMore
      });
    } catch (error) {
      if (this.requestId === run) this.setData(page > 0 ? { moreError: error.message } : { error: error.message });
    } finally {
      if (this.requestId === run) this.setData({ loading: false });
    }
  },

  loadMore() {
    if (!this.data.hasMore || this.data.loading || this.data.blocked || this.data.error) return;
    return this.load(this.data.page + 1);
  },

  search(event) { this.setData({ q: event.detail.value }); },
  submitSearch() { return this.load(0); },
  filter(event) {
    this.setData({ filter: Number(event.currentTarget.dataset.index) });
    return this.load(0);
  },
  details(event) { ui.go('/merchant/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); },
  aftersales() { ui.go('/merchant/pages/aftersales/index'); },

  update(event) {
    const { id, action } = event.currentTarget.dataset;
    ui.action(this, async () => {
      if (!await ui.confirm(...ui.fulfillmentConfirmation(action))) return;
      await api.request(ui.orderPath(id) + '/status', { action }, 'POST');
      await this.load(0);
      if (action === 'accept') wx.showToast({ title: '接单成功，小票已排队', icon: 'none' });
    });
  }
});
