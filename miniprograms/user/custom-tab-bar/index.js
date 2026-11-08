Component({
  data: { selected: 0, tabs: [
    { text: '首页', icon: '⌂', path: '/pages/home/index' },
    { text: '购物车', icon: '▤', path: '/pages/cart/index' },
    { text: '订单', icon: '▣', path: '/pages/orders/index' },
    { text: '我的', icon: '☺', path: '/pages/mine/index' }
  ], count: 0 },
  lifetimes: { attached() { this.refresh(); } },
  pageLifetimes: { show() { this.refresh(); } },
  methods: {
    refresh() {
      const pages = getCurrentPages();
      const current = pages.length ? pages[pages.length - 1].route : '';
      const selected = this.data.tabs.findIndex(tab => current === tab.path.slice(1));
      const cart = wx.getStorageSync('shoppingCart') || [];
      this.setData({ selected, count: cart.reduce((n, item) => n + item.quantity, 0) });
    },
    switchTab(event) { wx.switchTab({ url: event.currentTarget.dataset.path }); }
  }
});
