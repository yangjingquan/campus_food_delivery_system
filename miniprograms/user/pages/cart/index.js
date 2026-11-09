const cart = require('../../utils/cart');
const display = require('../../utils/display');
Page({
  data: { groups: [], totals: {}, feeText: '¥0.00', foodText: '¥0.00', totalText: '¥0.00', cartCount: 0, loading: true, loadError: '' },
  onShow() {
    this.getTabBar?.()?.refresh('pages/cart/index');
    this.load();
  },
  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      const config = await getApp().getConfig();
      const items = cart.read();
      const totals = cart.totals(items, config.deliveryFeeCents);
      const groups = cart.groups(items).map(group => ({
        ...group,
        items: group.items.map(item => ({ ...item, picture: display.photo(item.photoPath, item.artwork), fallback: display.illustration(item.artwork), priceText: `¥${(item.priceCents / 100).toFixed(2)}`, amountText: `¥${(item.priceCents * item.quantity / 100).toFixed(2)}` })),
        foodCents: group.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0),
        foodText: `¥${(group.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0) / 100).toFixed(2)}`,
        feeText: `¥${(config.deliveryFeeCents / 100).toFixed(2)}`
      }));
      this.setData({ loading: false, groups, totals, foodText: `¥${(totals.foodCents / 100).toFixed(2)}`, feeText: `¥${(totals.deliveryFeeCents / 100).toFixed(2)}`, totalText: `¥${(totals.totalCents / 100).toFixed(2)}`, cartCount: items.reduce((sum, item) => sum + item.quantity, 0) });
    } catch (error) { this.setData({ loading: false, loadError: error.message }); }
  },
  imageError(event) {
    const { group, index } = event.currentTarget.dataset;
    this.setData({ [`groups[${group}].items[${index}].picture`]: this.data.groups[group].items[index].fallback });
  },
  change(event) {
    const item = cart.read().find(row => row.productId === event.currentTarget.dataset.id);
    if (!item) return;
    const delta = Number(event.currentTarget.dataset.delta);
    const next = delta < 0 ? Math.max(0, item.quantity + delta) : Math.min(99, item.availableStock, item.quantity + delta);
    cart.save(cart.read().map(row => row.productId === item.productId ? { ...row, quantity: next } : row).filter(row => row.quantity > 0));
    this.load();
    const tab = this.getTabBar(); if (tab) tab.refresh();
  },
  clear() { wx.showModal({ title: '清空购物车', content: '确定移除所有餐品吗？', success: result => { if (result.confirm) { cart.save([]); this.load(); } } }); },
  checkout() { if (!this.data.cartCount) return; wx.navigateTo({ url: '/pages/confirm/index' }); },
  openStore(event) { wx.navigateTo({ url: `/pages/store/index?id=${event.currentTarget.dataset.id}` }); },
  openHome() { wx.switchTab({ url: '/pages/home/index' }); }
});
