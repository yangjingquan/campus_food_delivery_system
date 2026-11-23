const { openCart, openCheckout } = require('../../utils/navigation');
const api = require('../../utils/api');
const cart = require('../../utils/cart');
const display = require('../../utils/display');

Page({
  data: {
    merchant: null, products: [], selectedCount: 0, selectedFoodText: '¥0.00',
    fulfillmentType: 'delivery', belowMinimum: false, shortfallText: '', loading: true, loadError: ''
  },

  onLoad(query) {
    // 微信小程序码的 scene 是 32 位以内的商户编号。
    const scene = query.scene || '';
    this.merchantId = query.id || (/^[A-Za-z0-9_-]{1,32}$/.test(scene) ? scene : '');
    if (scene && this.merchantId) this.setData({ fulfillmentType: 'pickup' });
  },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      if (!this.merchantId) throw new Error('该店铺暂不可用');
      const { data } = await api.get('/catalog?merchantId=' + encodeURIComponent(this.merchantId), { auth: false });
      const merchant = data.merchants.find(item => item.id === this.merchantId);
      if (!merchant) throw new Error('该店铺暂不可用');
      const artwork = merchant.products[0]?.artwork;
      this.setData({
        merchant: {
          ...merchant, picture: display.photo(merchant.photoPath, artwork),
          fallback: display.illustration(artwork), deliveryFeeText: display.deliveryFee(merchant.deliveryFeeRule, merchant.deliveryFeeCents),
          minimumOrderText: display.money(merchant.minimumOrderCents || 0)
        }
      });
      this.refreshCart();
      this.setData({ loading: false });
    } catch (error) {
      this.setData({ merchant: null, loading: false, loadError: error.message });
    }
  },

  refreshCart() {
    const { merchant, fulfillmentType } = this.data;
    const selected = cart.read(merchant.id, fulfillmentType);
    const currentPrices = new Map(merchant.products.map(item => [item.id, item.priceCents]));
    const pricedItems = selected.map(item => ({
      ...item, priceCents: currentPrices.get(item.productId) ?? item.priceCents
    }));
    const { foodCents } = cart.totals(pricedItems, 0);
    const shortfall = fulfillmentType === 'pickup' ? 0 : (merchant.minimumOrderCents || 0) - foodCents;
    const products = merchant.products.map(item => ({
      ...item, priceText: display.money(item.priceCents),
      picture: display.photo(item.photoPath, item.artwork), fallback: display.illustration(item.artwork),
      quantity: selected.find(row => row.productId === item.id)?.quantity || 0
    }));
    this.setData({
      products, selectedCount: selected.reduce((n, item) => n + item.quantity, 0),
      selectedFoodText: display.money(foodCents),
      belowMinimum: shortfall > 0, shortfallText: shortfall > 0 ? display.money(shortfall) : ''
    });
  },

  imageError(event) {
    const { index } = event.currentTarget.dataset;
    if (index === undefined) this.setData({ 'merchant.picture': this.data.merchant.fallback });
    else this.setData({ [`products[${index}].picture`]: this.data.products[index].fallback });
  },

  change(event) {
    const { id, delta } = event.currentTarget.dataset;
    const { merchant, fulfillmentType } = this.data;
    const product = merchant.products.find(item => item.id === id);
    if (!product || (Number(delta) > 0 && (!merchant.open || !product.availableStock))) return;
    cart.add(cart.read(null, fulfillmentType), product, merchant, Number(delta), fulfillmentType);
    this.refreshCart();
  },

  chooseFulfillment(event) {
    const mode = event.currentTarget.dataset.mode;
    if (mode === this.data.fulfillmentType) return;
    this.setData({ fulfillmentType: mode });
    this.refreshCart();
  },

  viewCart() { return openCart(); },
  checkout() {
    const { merchant, loading, selectedCount, belowMinimum, fulfillmentType } = this.data;
    if (!merchant || loading || !selectedCount || belowMinimum) return;
    return openCheckout(merchant.id, fulfillmentType);
  }
});
