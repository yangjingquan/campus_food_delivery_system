const api = require('../../utils/api');
const cart = require('../../utils/cart');
Page({
  data: { merchants: [], config: {}, keyword: '', category: '全部', categories: ['全部', '米饭', '面食', '轻食'], loading: true, cartCount: 0 },
  onShow() {
    const address = wx.getStorageSync('selectedAddress');
    this.setData({ cartCount: cart.read().reduce((n, item) => n + item.quantity, 0), addressLabel: address ? `${address.building} · ${address.room}` : '添加收餐地址' });
    this.load();
  },
  async load() {
    try {
      const [catalog, config] = await Promise.all([api.get(`/catalog?keyword=${encodeURIComponent(this.data.keyword)}&category=${encodeURIComponent(this.data.category)}`), api.get('/config')]);
      getApp().globalData.config = config.data;
      const merchants = (catalog.data.merchants || []).map(item => ({ ...item, deliveryFeeText: `¥${(item.deliveryFeeCents / 100).toFixed(2)}` }));
      this.setData({ merchants, config: config.data, loading: false });
    } catch (error) { this.setData({ loading: false }); wx.showToast({ title: error.message || '暂时无法加载商户', icon: 'none' }); }
  },
  inputKeyword(event) { this.setData({ keyword: event.detail.value }); },
  search() { this.load(); },
  chooseCategory(event) { this.setData({ category: event.currentTarget.dataset.value }, () => this.load()); },
  openStore(event) { wx.navigateTo({ url: `/pages/store/index?id=${event.currentTarget.dataset.id}` }); },
  chooseAddress() { wx.navigateTo({ url: '/pages/addresses/index?choose=1' }); },
  startOrder() { wx.navigateTo({ url: '/pages/cart/index' }); },
  showRules() { wx.showModal({ title: '配送说明', content: `配送时段 ${this.data.config.serviceStart || '以平台配置为准'}–${this.data.config.serviceEnd || ''}；高峰每 ${this.data.config.peakBatchMinutes || '—'} 分钟一批，其他时段每 ${this.data.config.regularBatchMinutes || '—'} 分钟一批。预计送达时间不作承诺。`, showCancel: false }); }
});
