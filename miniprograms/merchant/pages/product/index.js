const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true, form: { name: '', category: '餐品', description: '', stock: '', price: '', available: true } },
  onLoad(options) { this.id = options.id || ''; this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      if (!this.id) return;
      const path = '/products/' + encodeURIComponent(this.id);
      const [products, history] = await Promise.all([api.request('/products'), api.request(path + '/stock-events')]);
      const item = products.find(product => product.id === this.id);
      if (!item) throw new Error('商品不存在');
      this.previousStock = item.stock;
      this.setData({
        id: this.id,
        form: { ...item, price: (item.priceCents / 100).toFixed(2), stock: String(item.stock) },
        history: history.map(row => ({ ...row, time: ui.date(row.createdAt) }))
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  input(event) { this.setData({ ['form.' + event.currentTarget.dataset.field]: event.detail.value }); },
  availability(event) { this.setData({ 'form.available': event.detail.value }); },
  save() {
    ui.action(this, async () => {
      const { name, category, description, stock, price, available } = this.data.form;
      if (!name.trim() || !category.trim()) throw new Error('请填写商品名称与分类');
      if (!/^\d{1,7}$/.test(stock)) throw new Error('库存须为非负整数');
      const body = { name, category, description, stock: Number(stock), priceCents: ui.cents(price), available };
      if (this.id) body.previousStock = this.previousStock;
      await api.request(this.id ? '/products/' + encodeURIComponent(this.id) : '/products', body, this.id ? 'PUT' : 'POST');
      wx.navigateBack();
    });
  }
});
