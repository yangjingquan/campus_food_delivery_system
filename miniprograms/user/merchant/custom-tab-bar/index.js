const tabs = [
  { key: 'orders', text: '订单', icon: 'orders', path: '/merchant/pages/orders/index' },
  { key: 'products', text: '商品', icon: 'products', path: '/merchant/pages/products/index' },
  { key: 'finance', text: '经营', icon: 'finance', path: '/merchant/pages/finance/index' },
  { key: 'mine', text: '我的', icon: 'mine', path: '/merchant/pages/mine/index' }
];

Component({
  properties: {
    selected: String,
    inPlace: Boolean,
    hiddenBar: Boolean
  },
  data: { tabs },
  methods: {
    select(event) {
      const { path: url, key } = event.currentTarget.dataset;
      if (url === this.data.selected) return;
      if (this.data.inPlace) {
        this.triggerEvent('switch', { path: url, key });
      } else {
        wx.redirectTo({ url: key === 'orders' ? '/merchant/pages/orders/index' : '/merchant/pages/orders/index?tab=' + key });
      }
    }
  }
});
