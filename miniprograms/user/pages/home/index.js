const api = require('../../utils/api');
const display = require('../../utils/display');

const { showDialog, dialogEvents } = require('../../utils/dialog');

Page({
  ...dialogEvents,
  data: {
    merchants: [], config: {}, keyword: '', category: '全部',
    categories: ['全部'], loading: true, loadError: '',
    addressLabel: '添加收餐地址'
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/home/index');
    const address = wx.getStorageSync('selectedAddress');
    this.setData({ addressLabel: address ? `${address.building} · ${address.room}` : '添加收餐地址' });
    this.load();
  },

  async load() {
    const request = (this.request || 0) + 1;
    this.request = request;
    this.setData({ loading: true, loadError: '' });
    try {
      const { keyword, category } = this.data;
      const query = `keyword=${encodeURIComponent(keyword.trim())}&category=${encodeURIComponent(category)}`;
      const [{ data }, config] = await Promise.all([
        api.get('/catalog?' + query, { auth: false }),
        getApp().getConfig()
      ]);
      // 搜索和筛选可能同时发起，旧响应不能覆盖最新结果。
      if (request !== this.request) return;
      const categories = Array.isArray(data.categories) ? ['全部', ...data.categories] : this.data.categories;
      if (!categories.includes(category)) {
        this.setData({ categories, category: '全部' });
        return this.load();
      }
      const merchants = (data.merchants || []).map(item => {
        const artwork = item.products[0]?.artwork;
        return {
          ...item, picture: display.photo(item.photoPath, artwork),
          fallback: display.illustration(artwork), deliveryFeeText: display.money(item.deliveryFeeCents)
        };
      });
      this.setData({ merchants, config, categories, loading: false });
    } catch (error) {
      if (request === this.request) this.setData({ loading: false, loadError: error.message });
    }
  },

  imageError(event) {
    const { index } = event.currentTarget.dataset;
    this.setData({ [`merchants[${index}].picture`]: this.data.merchants[index].fallback });
  },

  inputKeyword(event) { this.setData({ keyword: event.detail.value }); },
  search() { this.load(); },
  chooseCategory(event) { this.setData({ category: event.currentTarget.dataset.value }); this.load(); },
  openStore(event) {
    wx.navigateTo({ url: `/pages/store/index?id=${encodeURIComponent(event.currentTarget.dataset.id)}` });
  },
  chooseAddress() { wx.navigateTo({ url: '/pages/addresses/index?choose=1' }); },
  startOrder() { wx.pageScrollTo({ selector: '#catalog', duration: 250 }); },
  showRules() {
    const c = this.data.config;
    showDialog(this, {
      title: '配送说明',
      content: c.serviceStart
        ? `配送时段 ${c.serviceStart}–${c.serviceEnd}。高峰每 ${c.peakBatchMinutes} 分钟一批，其他时段每 ${c.regularBatchMinutes} 分钟一批。`
        : '暂时无法读取配送时段，请稍后重试。'
    });
  }
});
