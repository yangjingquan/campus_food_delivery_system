const api = require('../../utils/api');
const ui = require('../../utils/ui');
const { choosePhoto } = require('../../utils/photo');

Page({
  data: { loading: true, categories: [], categoryIndex: 0, form: { name: '', category: '', description: '', stock: '', price: '', available: true } },
  onLoad(options) {
    this.id = options.id || '';
    wx.setNavigationBarTitle({ title: this.id ? '编辑商品' : '新增商品' });
    this.refresh();
  },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const categories = await api.request('/products/categories');
      if (!this.id) {
        const current = this.data.form.category;
        const category = categories.includes(current) ? current : (categories[0] || '');
        this.setData({ categories, categoryIndex: Math.max(0, categories.indexOf(category)), 'form.category': category });
        return;
      }
      const path = '/products/' + encodeURIComponent(this.id);
      const [products, history] = await Promise.all([api.request('/products'), api.request(path + '/stock-events')]);
      const item = products.find(product => product.id === this.id);
      if (!item) throw new Error('商品不存在');
      this.previousStock = item.stock;
      this.setData({
        id: this.id,
        categories,
        categoryIndex: Math.max(0, categories.indexOf(item.category)),
        form: { ...item, price: (item.priceCents / 100).toFixed(2), stock: String(item.stock) },
        history: history.map(row => ({ ...row, time: ui.date(row.createdAt) }))
      });
      if (!this.image && item.hasPhoto) {
        try {
          this.setData({ photo: await api.photo(path + '/photo'), photoError: '' });
        } catch (error) {
          this.setData({ photoError: error.message });
        }
      }
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  input(event) { this.setData({ ['form.' + event.currentTarget.dataset.field]: event.detail.value }); },
  selectCategory(event) {
    const categoryIndex = Number(event.detail.value);
    const category = this.data.categories[categoryIndex];
    if (category !== undefined) this.setData({ categoryIndex, 'form.category': category });
  },
  availability(event) { this.setData({ 'form.available': event.detail.value }); },
  async choosePhoto() {
    if (this.data.choosingPhoto || this.data.saving) return;
    this.setData({ choosingPhoto: true, photoError: '' });
    try {
      const photo = await choosePhoto();
      if (!photo) return;
      this.image = photo.dataUrl;
      this.setData({ photo: photo.path, formError: '' });
    } catch (error) {
      this.setData({ photoError: error.message });
    } finally {
      this.setData({ choosingPhoto: false });
    }
  },
  save() {
    if (this.data.choosingPhoto) return;
    ui.action(this, async () => {
      const { name, category, description, stock, price, available } = this.data.form;
      if (!name.trim() || !category.trim()) throw new Error('请填写商品名称并选择分类');
      if (!/^\d{1,7}$/.test(stock)) throw new Error('库存须为非负整数');
      if (!this.id && !this.image) throw new Error('请上传一张商品图片');
      const body = { name, category, description, stock: Number(stock), priceCents: ui.cents(price), available };
      if (this.image) body.image = this.image;
      if (this.id) body.previousStock = this.previousStock;
      await api.request(this.id ? '/products/' + encodeURIComponent(this.id) : '/products', body, this.id ? 'PUT' : 'POST');
      wx.navigateBack();
    });
  }
});
