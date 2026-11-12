const tabs = [
  { text: '订单', icon: 'orders', path: '/pages/orders/index' },
  { text: '商品', icon: 'products', path: '/pages/products/index' },
  { text: '经营', icon: 'finance', path: '/pages/finance/index' },
  { text: '我的', icon: 'mine', path: '/pages/mine/index' }
];

Component({
  data: { tabs, selected: tabs[0].path },
  lifetimes: { attached() { this.syncTab(); } },
  pageLifetimes: { show() { this.syncTab(); } },
  methods: {
    syncTab() {
      const pages = getCurrentPages();
      const current = pages[pages.length - 1];
      const route = current?.route;
      if (route) this.setData({ selected: route.startsWith('/') ? route : '/' + route });
    },
    switchTab(event) {
      const url = event.currentTarget.dataset.path;
      if (url !== this.data.selected) wx.switchTab({ url });
    }
  }
});
