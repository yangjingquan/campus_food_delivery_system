const { openCart, openCheckout } = require('../../utils/navigation');
const api = require('../../utils/api');
const cart = require('../../utils/cart');
const display = require('../../utils/display');

Page({
  data: {
    merchant: null, products: [], visibleProducts: [], categories: [], activeCategory: '全部',
    selectedItems: [], selectedCount: 0, otherModeCount: 0, selectedFoodText: '¥0.00',
    cartDrawerOpen: false,
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
    const { merchant, fulfillmentType, activeCategory } = this.data;
    const selected = cart.read(merchant.id, fulfillmentType);
    const otherMode = fulfillmentType === 'pickup' ? 'delivery' : 'pickup';
    const otherModeCount = cart.read(merchant.id, otherMode).reduce((n, item) => n + item.quantity, 0);
    const currentPrices = new Map(merchant.products.map(item => [item.id, item.priceCents]));
    const pricedItems = selected.map(item => ({
      ...item, priceCents: currentPrices.get(item.productId) ?? item.priceCents
    }));
    const { foodCents } = cart.totals(pricedItems, 0);
    const shortfall = fulfillmentType === 'pickup' ? 0 : (merchant.minimumOrderCents || 0) - foodCents;
    const rawCategories = [...new Set(merchant.products.map(item => item.category).filter(Boolean))];
    const categories = rawCategories.length > 1 ? ['全部', ...rawCategories] : [];
    const currentCategory = categories.includes(activeCategory) ? activeCategory : '全部';
    const products = merchant.products.map(item => ({
      ...item, priceText: display.money(item.priceCents),
      picture: display.photo(item.photoPath, item.artwork), fallback: display.illustration(item.artwork),
      quantity: selected.find(row => row.productId === item.id)?.quantity || 0
    }));
    const visibleProducts = currentCategory === '全部'
      ? products
      : products.filter(item => item.category === currentCategory);
    const selectedItems = products.filter(item => item.quantity > 0);
    const selectedCount = selected.reduce((n, item) => n + item.quantity, 0);
    this.setData({
      products, visibleProducts, categories, activeCategory: currentCategory,
      selectedItems, selectedCount, otherModeCount,
      cartDrawerOpen: selectedCount ? this.data.cartDrawerOpen : false,
      selectedFoodText: display.money(foodCents),
      belowMinimum: shortfall > 0, shortfallText: shortfall > 0 ? display.money(shortfall) : ''
    });
  },

  chooseCategory(event) {
    const activeCategory = event.currentTarget.dataset.category;
    if (activeCategory === this.data.activeCategory) return;
    const visibleProducts = activeCategory === '全部'
      ? this.data.products
      : this.data.products.filter(item => item.category === activeCategory);
    this.setData({ activeCategory, visibleProducts });
  },

  syncFromOtherMode() {
    const { merchant, fulfillmentType } = this.data;
    if (!merchant) return;
    const otherMode = fulfillmentType === 'pickup' ? 'delivery' : 'pickup';
    const otherItems = cart.read(merchant.id, otherMode);
    if (!otherItems.length) return;
    const currentAll = cart.read(null, fulfillmentType).filter(item => item.merchantId !== merchant.id);
    cart.save([...currentAll, ...otherItems.map(item => ({ ...item }))], fulfillmentType);
    this.refreshCart();
  },

  toggleCartDrawer() {
    if (!this.data.selectedCount) return;
    this.setData({ cartDrawerOpen: !this.data.cartDrawerOpen });
  },
  closeCartDrawer() {
    if (this.data.cartDrawerOpen) this.setData({ cartDrawerOpen: false });
  },
  clearStoreCart() {
    const { merchant, fulfillmentType } = this.data;
    if (!merchant) return;
    const remaining = cart.read(null, fulfillmentType).filter(item => item.merchantId !== merchant.id);
    cart.save(remaining, fulfillmentType);
    this.setData({ cartDrawerOpen: false });
    this.refreshCart();
  },
  noop() {},

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
