const { openCart } = require('../utils/navigation');

Component({
  data: { selected: 0, tabs: [
    { text: '点餐', icon: 'meal', path: '/pages/home/index' },
    { text: '订单', icon: 'orders', path: '/pages/orders/index' },
    { text: '我的', icon: 'mine', path: '/pages/mine/index' }
  ], count: 0 },
  lifetimes: { attached() { this.refresh(); } },
  pageLifetimes: { show() { this.refresh(); } },
  methods: {
    refresh(route) {
      const pages = getCurrentPages();
      const current = route || (pages.length ? pages[pages.length - 1].route : '');
      const selected = this.data.tabs.findIndex(tab => current === tab.path.slice(1));
      const cart = wx.getStorageSync('shoppingCart') || [];
      this.setData({ selected: selected < 0 ? this.data.selected : selected, count: cart.reduce((n, item) => n + item.quantity, 0) });
    },
    openCart,
    switchTab(event) { wx.switchTab({ url: event.currentTarget.dataset.path }); }
  }
});
