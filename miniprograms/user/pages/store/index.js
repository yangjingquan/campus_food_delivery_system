const { openCart } = require('../../utils/navigation');
const api = require('../../utils/api');
const cart = require('../../utils/cart');
const display = require('../../utils/display');

Page({
  data: { merchant: null, products: [], selectedCount: 0, selectedFoodText: '¥0.00', loading: true, loadError: '' },

  onLoad(query) { this.merchantId = query.id; },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      const { data } = await api.get('/catalog?merchantId=' + encodeURIComponent(this.merchantId), { auth: false });
      const merchant = data.merchants.find(item => item.id === this.merchantId);
      if (!merchant) throw new Error('该店铺暂不可用');
      const artwork = merchant.products[0]?.artwork;
      this.setData({
        merchant: {
          ...merchant, picture: display.photo(merchant.photoPath, artwork),
          fallback: display.illustration(artwork), deliveryFeeText: display.money(merchant.deliveryFeeCents),
          minimumOrderText: display.money(merchant.minimumOrderCents || 0)
        },
        loading: false
      });
      this.refreshCart();
    } catch (error) {
      this.setData({ merchant: null, loading: false, loadError: error.message });
    }
  },

  refreshCart() {
    const selected = cart.read();
    const products = this.data.merchant.products.map(item => ({
      ...item, priceText: display.money(item.priceCents),
      picture: display.photo(item.photoPath, item.artwork), fallback: display.illustration(item.artwork),
      quantity: selected.find(row => row.productId === item.id)?.quantity || 0
    }));
    this.setData({
      products, selectedCount: selected.reduce((n, item) => n + item.quantity, 0),
      selectedFoodText: display.money(cart.totals(selected, 0).foodCents)
    });
  },

  imageError(event) {
    const { index } = event.currentTarget.dataset;
    if (index === undefined) this.setData({ 'merchant.picture': this.data.merchant.fallback });
    else this.setData({ [`products[${index}].picture`]: this.data.products[index].fallback });
  },

  change(event) {
    const { id, delta } = event.currentTarget.dataset;
    const { merchant } = this.data;
    const product = merchant.products.find(item => item.id === id);
    if (!product || (Number(delta) > 0 && (!merchant.open || !product.availableStock))) return;
    cart.add(cart.read(), product, merchant, Number(delta));
    this.refreshCart();
  },

  viewCart() { return openCart(); }
});
