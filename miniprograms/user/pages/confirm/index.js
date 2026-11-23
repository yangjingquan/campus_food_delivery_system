const { openCart } = require('../../utils/navigation');
const api = require('../../utils/api');
const { hasCoordinates } = require('../../utils/coordinates');
const cart = require('../../utils/cart');
const { money, photo, illustration } = require('../../utils/display');

Page({
  data: {
    groups: [], totals: {}, address: null, foodText: '¥0.00', feeText: '¥0.00', totalText: '¥0.00',
    config: {}, loading: true, loadError: '', submitting: false, unavailable: false, belowMinimum: false
  },

  onLoad(query) {
    const pickup = query.fulfillmentType === 'pickup';
    this.setData({ pickup });
    this.merchantScope = pickup ? [query.merchantId].filter(Boolean) : query.merchantIds === undefined
      ? query.merchantId
      : query.merchantIds.split(',').filter(Boolean).map(id => decodeURIComponent(id));
  },
  onShow() { this.load(); },

  async load() {
    if (this.data.submitting) return;
    this.setData({ loading: true, loadError: '' });
    try {
      this.user = await getApp().ensureLogin();
      const [{ data: addresses }, { data: catalog }, config] = await Promise.all([
        this.data.pickup ? { data: {} } : api.get('/addresses'),
        api.get('/catalog', { auth: false }), getApp().getConfig(true)
      ]);
      const stored = wx.getStorageSync('selectedAddress');
      const rows = addresses.rows || [];
      const address = rows.find(item => item.id === stored?.id) || rows.find(item => item.isDefault) || rows[0] || null;
      if (address) wx.setStorageSync('selectedAddress', address);

      const mode = this.data.pickup ? 'pickup' : 'delivery';
      const latest = new Map();
      const merchants = new Map();
      for (const merchant of catalog.merchants || []) {
        merchants.set(merchant.id, merchant);
        for (const product of merchant.products) latest.set(product.id, { product, merchant });
      }
      this.items = cart.read(this.merchantScope, mode).map(item => {
        const entry = latest.get(item.productId);
        const unavailable = !entry || !entry.merchant.open || entry.product.availableStock < item.quantity;
        return {
          ...item, unavailable,
          priceCents: entry?.product.priceCents ?? item.priceCents,
          name: entry?.product.name ?? item.name,
          photoPath: entry?.product.photoPath ?? item.photoPath
        };
      });
      const fee = merchantId => this.data.pickup ? 0 : cart.feeRule(merchants.get(merchantId), config);
      const totals = cart.totals(this.items, fee);
      const groups = cart.groups(this.items).map(group => {
        const amounts = cart.totals(group.items, fee);
        const minimum = this.data.pickup ? 0 : (merchants.get(group.merchantId)?.minimumOrderCents || 0);
        const shortfall = minimum - amounts.foodCents;
        return {
          ...group,
          items: group.items.map(item => ({
            ...item, amountText: money(item.priceCents * item.quantity),
            picture: photo(item.photoPath, item.artwork), fallback: illustration(item.artwork)
          })),
          feeText: money(amounts.deliveryFeeCents),
          orderText: money(amounts.totalCents),
          minimumText: minimum ? money(minimum) : '',
          shortfallText: shortfall > 0 ? money(shortfall) : ''
        };
      });
      this.setData({
        groups, totals, address,
        pickupAddress: this.data.pickup ? latest.get(this.items[0]?.productId)?.merchant.address || '' : '',
        addressNeedsLocation: !!address && !hasCoordinates(address), config, loading: false,
        unavailable: this.items.some(item => item.unavailable),
        belowMinimum: groups.some(group => group.shortfallText),
        paymentEnabled: ['mock', 'wechat'].includes(config.paymentMode),
        foodText: money(totals.foodCents), feeText: money(totals.deliveryFeeCents), totalText: money(totals.totalCents)
      });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  imageError(event) {
    const { group, index } = event.currentTarget.dataset;
    const item = this.data.groups[group]?.items[index];
    if (item && item.picture !== item.fallback) {
      this.setData({ [`groups[${group}].items[${index}].picture`]: item.fallback });
    }
  },

  chooseAddress() { wx.navigateTo({ url: '/pages/addresses/index?choose=1' }); },
  completeAddress() {
    if (this.data.address) wx.navigateTo({ url: `/pages/address-edit/index?id=${encodeURIComponent(this.data.address.id)}` });
  },
  openCart() { return this.data.pickup ? wx.navigateBack() : openCart(); },

  async submit() {
    if (this.data.submitting || this.data.loading || this.data.unavailable || this.data.belowMinimum || !this.data.paymentEnabled) return;
    const { pickup, address } = this.data;
    if (!pickup && !address) return this.chooseAddress();
    if (!pickup && !hasCoordinates(address)) return this.completeAddress();
    const items = this.items.map(({ productId, quantity }) => ({ productId, quantity }));
    if (!items.length) return;
    this.setData({ submitting: true, submitError: '' });

    const fulfillment = pickup ? { fulfillmentType: 'pickup' } : { addressId: address.id };
    const expectedTotalCents = this.data.totals.totalCents;
    const fingerprint = JSON.stringify([this.user.id, pickup ? 'pickup' : address.id, expectedTotalCents, items]);
    const attemptKey = pickup ? 'pickupCheckoutAttempt' : 'checkoutAttempt';
    let attempt = wx.getStorageSync(attemptKey);
    // 两种取餐方式独立保存重试编号，切换方式不会丢失尚未确认的请求。
    if (attempt?.fingerprint !== fingerprint) {
      attempt = { fingerprint, requestKey: `mini_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` };
      wx.setStorageSync(attemptKey, attempt);
    }

    let refreshQuote = false;
    try {
      const { data } = await api.post('/checkout', { ...fulfillment, requestKey: attempt.requestKey, expectedTotalCents, items });
      const payment = data.payment;
      if (payment.status === 'CLOSED') {
        wx.removeStorageSync(attemptKey);
        this.setData({ submitError: '上一笔支付单已关闭，请重新提交。' });
        return;
      }
      wx.setStorageSync(attemptKey, { ...attempt, paymentId: payment.id });
      wx.setStorageSync('cartPayment:' + payment.id, {
        userId: this.user.id,
        fulfillmentType: pickup ? 'pickup' : 'delivery',
        items: this.items.map(({ productId, quantity, lineKey }) => ({ productId, quantity, lineKey: lineKey || '' }))
      });
      const page = payment.status === 'PAYMENT_SUCCEEDED' ? 'payment-result' : 'payment';
      wx.navigateTo({ url: `/pages/${page}/index?id=${encodeURIComponent(payment.id)}${pickup && page === 'payment' ? '&autoPay=1' : ''}` });
    } catch (error) {
      this.setData({ submitError: error.message || '提交结果暂未确认，请重试' });
      if (error.statusCode === 409) {
        wx.removeStorageSync(attemptKey);
        refreshQuote = true;
      }
    } finally {
      this.setData({ submitting: false });
    }
    if (refreshQuote) await this.load();
  }
});
