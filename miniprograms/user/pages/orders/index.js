const api = require('../../utils/api');
const { money, time } = require('../../utils/display');

Page({
  data: {
    visible: [], filter: 'ALL', page: 0, hasMore: false,
    filters: ['ALL', 'PENDING_PAYMENT', 'IN_PROGRESS', 'COMPLETED', 'CLOSED', 'AFTER_SALE'],
    loading: true, loadError: '', loadingMore: false, loadMoreError: ''
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/orders/index');
    this.load();
  },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { return this.loadMore(); },

  load() {
    // 切换筛选或重新加载后，旧请求即使晚返回也不能追加到当前列表。
    const version = this.listVersion = (this.listVersion || 0) + 1;
    this.setData({
      visible: [], page: 0, hasMore: false,
      loading: true, loadError: '', loadingMore: false, loadMoreError: ''
    });
    return this.loadPage(1, version);
  },

  loadMore() {
    const { page, hasMore, loading, loadError, loadingMore } = this.data;
    if (!hasMore || loading || loadError || loadingMore) return;
    this.setData({ loadingMore: true, loadMoreError: '' });
    return this.loadPage(page + 1, this.listVersion);
  },

  async loadPage(page, version) {
    const status = encodeURIComponent(this.data.filter);
    try {
      await getApp().ensureLogin();
      if (version !== this.listVersion) return;
      const { data } = await api.get(`/orders?status=${status}&page=${page}`);
      if (version !== this.listVersion) return;
      const rows = (data.rows || []).map(row => ({
        ...row, amountText: money(row.paidCents), displayItems: row.items,
        displayStatus: row.isPayment ? 'PENDING_PAYMENT' : row.fulfillStatus,
        note: time(row.createdAt)
      }));
      const visible = new Map((page === 1 ? [] : this.data.visible).map(row => [row.id, row]));
      for (const row of rows) visible.set(row.id, row);
      this.setData({ visible: [...visible.values()], page, hasMore: data.hasMore === true });
    } catch (error) {
      if (version === this.listVersion) {
        this.setData(page === 1 ? { loadError: error.message } : { loadMoreError: error.message });
      }
    } finally {
      if (version === this.listVersion) this.setData({ loading: false, loadingMore: false });
    }
  },

  chooseFilter(event) {
    const filter = event.currentTarget.dataset.value;
    if (filter === this.data.filter) return;
    this.setData({ filter });
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    return this.load();
  },

  openEntry(event) {
    const { id, payment } = event.currentTarget.dataset;
    if (payment) wx.navigateTo({ url: `/pages/payment/index?id=${id}` });
    else wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` });
  },
  openOrder(event) { wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` }); },
  openPayment(event) { wx.navigateTo({ url: `/pages/payment/index?id=${event.currentTarget.dataset.id}` }); },
  goHome() { wx.switchTab({ url: '/pages/home/index' }); }
});
