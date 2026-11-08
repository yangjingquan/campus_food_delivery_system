const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { rows: [], visible: [], filter: 0, labels: ['全部商品', '在售', '已下架', '已售罄'], loading: true },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onboarding: ui.onboarding,

  async refresh() {
    this.setData({ loading: true, error: '', blocked: '' });
    try {
      const access = await ui.access();
      this.setData(access);
      if (access.blocked) return;
      const rows = (await api.request('/products')).map(item => ({
        ...item, price: ui.money(item.priceCents),
        status: !item.available ? '已下架' : item.availableStock <= 0 ? '已售罄' : '在售'
      }));
      this.setData({ rows });
      this.applyFilter();
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },

  select(event) {
    this.setData({ filter: Number(event.currentTarget.dataset.index) });
    this.applyFilter();
  },

  applyFilter() {
    const { rows, labels, filter } = this.data;
    this.setData({ visible: filter ? rows.filter(item => item.status === labels[filter]) : rows });
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
