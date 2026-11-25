const tabs = [
  { text: '订单', icon: 'orders', path: '/merchant/pages/orders/index' },
  { text: '商品', icon: 'products', path: '/merchant/pages/products/index' },
  { text: '经营', icon: 'finance', path: '/merchant/pages/finance/index' },
  { text: '我的', icon: 'mine', path: '/merchant/pages/mine/index' }
];

Component({
  properties: { selected: String },
  data: { tabs },
  methods: {
    select(event) {
      const url = event.currentTarget.dataset.path;
      if (url !== this.data.selected) wx.redirectTo({ url });
    }
  }
});
