const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { rows: [], visible: [], thumbnails: {}, filter: 0, labels: ['全部商品', '在售', '已下架', '已售罄'], loading: true },
  onShow() {
    this.pageVisible = true;
    const tabBar = this.getTabBar?.();
    if (tabBar) tabBar.setData({ selected: '/pages/products/index' });
    this.refresh();
  },
  onHide() {
    this.pageVisible = false;
    this.stopThumbnails();
  },
  onUnload() { this.onHide(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onboarding: ui.onboarding,

  async refresh() {
    this.stopThumbnails();
    this.setData({ loading: true, error: '', blocked: '', thumbnails: {} });
    try {
      const access = await ui.access();
      this.setData(access);
      if (access.blocked) return;
      const rows = (await api.request('/products')).map(item => ({
        ...item, price: ui.money(item.priceCents),
        status: !item.available ? '已下架' : item.availableStock <= 0 ? '已售罄' : '在售'
      }));
      this.setData({ rows });
      this.applyFilter(false);
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false }, () => this.observeThumbnails());
    }
  },

  select(event) {
    this.setData({ filter: Number(event.currentTarget.dataset.index) });
    this.applyFilter();
  },

  applyFilter(observe = true) {
    const { rows, labels, filter } = this.data;
    const visible = filter ? rows.filter(item => item.status === labels[filter]) : rows;
    this.setData({ visible }, observe ? () => this.observeThumbnails() : undefined);
  },

  stopThumbnails() {
    if (this.thumbnailObserver) this.thumbnailObserver.disconnect();
    this.thumbnailObserver = null;
  },

  observeThumbnails() {
    this.stopThumbnails();
    const { visible, loading, blocked, error } = this.data;
    if (!this.pageVisible || loading || blocked || error || !visible.some(item => item.hasPhoto)) return;

    const requested = new Set();
    const observer = this.createIntersectionObserver({ observeAll: true });
    this.thumbnailObserver = observer;
    observer.relativeToViewport().observe('.load-thumbnail', async entry => {
      const id = entry.dataset.id;
      if (this.thumbnailObserver !== observer || !entry.intersectionRatio) return;
      if (requested.has(id) || this.data.thumbnails[id]) return;
      requested.add(id);
      try {
        const path = await api.photo('/products/' + encodeURIComponent(id) + '/photo');
        if (this.thumbnailObserver === observer) this.setData({ thumbnails: { ...this.data.thumbnails, [id]: path } });
      } catch {
        // 图片加载失败时保留占位图，商品仍可正常编辑。
      }
    });
  },

  edit(event) {
    const id = event.currentTarget.dataset.id;
    ui.go('/pages/product/index' + (id ? '?id=' + encodeURIComponent(id) : ''));
  },

  toggle(event) {
    const item = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    ui.action(this, async () => {
      if (!await ui.confirm(item.available ? '确认下架' : '确认上架', item.name)) return;
      await api.request('/products/' + encodeURIComponent(item.id) + '/availability', { available: !item.available }, 'PUT');
      await this.refresh();
    });
  }
});
