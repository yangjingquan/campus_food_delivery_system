const api = require('../../utils/api');
const cart = require('../../utils/cart');
const display = require('../../utils/display');
const { showDialog, dialogEvents } = require('../../utils/dialog');
const { openCheckout } = require('../../utils/navigation');

Page({
  ...dialogEvents,
  data: {
    groups: [], checkoutCount: 0, feeText: '¥0.00', foodText: '¥0.00', totalText: '¥0.00',
    cartCount: 0, loading: true, loadError: ''
  },
  onShow() {
    this.load();
  },
  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      if (cart.read().length) {
        const [config, { data }] = await Promise.all([
          getApp().getConfig(true), api.get('/catalog', { auth: false })
        ]);
        this.feePerMerchant = config.deliveryFeeCents;
        this.merchants = new Map(data.merchants.map(merchant => [merchant.id, merchant]));
      }
      this.refreshCart();
      this.setData({ loading: false });
    } catch (error) { this.setData({ loading: false, loadError: error.message }); }
  },
  refreshCart() {
    const items = cart.read();
    const feePerMerchant = this.feePerMerchant ?? 0;
    const groups = cart.groups(items).map(group => {
      const merchant = this.merchants.get(group.merchantId);
      const prices = new Map((merchant?.products || []).map(product => [product.id, product.priceCents]));
      const lines = group.items.map(item => {
        const priceCents = prices.get(item.productId) ?? item.priceCents;
        return {
          ...item, priceCents, priceText: display.money(priceCents),
          picture: display.photo(item.photoPath, item.artwork), fallback: display.illustration(item.artwork)
        };
      });
      const { foodCents } = cart.totals(lines, 0);
      const shortfall = (merchant?.minimumOrderCents || 0) - foodCents;
      return {
        ...group, items: lines, eligible: !!merchant && shortfall <= 0,
        shortfallText: shortfall > 0 ? display.money(shortfall) : '',
        feeText: display.money(feePerMerchant)
      };
    });
    const totals = cart.totals(groups.flatMap(group => group.items), feePerMerchant);
    this.setData({
      groups, checkoutCount: groups.filter(group => group.eligible).length,
      foodText: display.money(totals.foodCents), feeText: display.money(totals.deliveryFeeCents),
      totalText: display.money(totals.totalCents),
      cartCount: items.reduce((sum, item) => sum + item.quantity, 0)
    });
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
    this.refreshCart();
    const tab = this.getTabBar(); if (tab) tab.refresh();
  },
  clear() {
    showDialog(this, { title: '清空购物车', content: '确定移除所有餐品吗？', cancel: true }, () => {
      cart.save([]);
      this.refreshCart();
    });
  },
  checkout() {
    if (this.data.loading || this.data.loadError || !this.data.checkoutCount) return;
    const merchantIds = this.data.groups.filter(group => group.eligible).map(group => group.merchantId);
    return openCheckout(merchantIds);
  },
  openStore(event) { wx.navigateTo({ url: `/pages/store/index?id=${event.currentTarget.dataset.id}` }); },
  openHome() { wx.switchTab({ url: '/pages/home/index' }); }
});
