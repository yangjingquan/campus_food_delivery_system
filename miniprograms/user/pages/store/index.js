const api = require('../../utils/api');
const cart = require('../../utils/cart');
Page({
  data: { merchant: null, selected: [], products: [], config: {}, selectedCount: 0, selectedFoodText: '¥0.00' },
  async onLoad(query) { this.merchantId = query.id; await this.load(); },
  onShow() { this.refreshCart(); },
  refreshCart() {
    const selected = cart.read();
    const merchant = this.data.merchant;
    const products = merchant ? merchant.products.map(item => ({ ...item, priceText: `¥${(item.priceCents / 100).toFixed(2)}`, quantity: (selected.find(row => row.productId === item.id) || {}).quantity || 0 })) : [];
    this.setData({ selected, products, selectedCount: selected.reduce((n, item) => n + item.quantity, 0), selectedFoodText: `¥${(selected.reduce((n, item) => n + item.priceCents * item.quantity, 0) / 100).toFixed(2)}` });
  },
  async load() {
    try {
      const [result, config] = await Promise.all([api.get('/catalog'), api.get('/config')]);
      const merchant = result.data.merchants.find(item => item.id === this.merchantId);
      if (!merchant) throw new Error('商户暂不可用');
      merchant.deliveryFeeText = `¥${(merchant.deliveryFeeCents / 100).toFixed(2)}`;
      this.setData({ merchant, config: config.data }, () => this.refreshCart());
    } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
  },
  change(event) {
    const product = this.data.merchant.products.find(item => item.id === event.currentTarget.dataset.id);
    if (!this.data.merchant.open || !product.availableStock) return wx.showToast({ title: '商户打烊或餐品已售罄', icon: 'none' });
    cart.add(cart.read(), product, this.data.merchant, Number(event.currentTarget.dataset.delta));
    this.refreshCart();
    const tab = this.getTabBar(); if (tab) tab.refresh();
  },
  viewCart() { wx.navigateTo({ url: '/pages/cart/index' }); },
  contact() { wx.showToast({ title: '商户联系方式由平台提供', icon: 'none' }); },
  money(cents) { return `¥${(Number(cents || 0) / 100).toFixed(2)}`; }
});
