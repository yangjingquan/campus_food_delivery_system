const { openCart } = require('../../utils/navigation');
const api = require('../../utils/api');
const cart = require('../../utils/cart');
const { money, photo, illustration } = require('../../utils/display');

Page({
  data: {
    groups: [], totals: {}, address: null, foodText: '¥0.00', feeText: '¥0.00', totalText: '¥0.00',
    config: {}, loading: true, loadError: '', submitting: false, unavailable: false, belowMinimum: false
  },

  onShow() { this.load(); },

  async load() {
    if (this.data.submitting) return;
    this.setData({ loading: true, loadError: '' });
    try {
      this.user = await getApp().ensureLogin();
      const [{ data: addresses }, { data: catalog }, config] = await Promise.all([
        api.get('/addresses'), api.get('/catalog', { auth: false }), getApp().getConfig(true)
      ]);
      const stored = wx.getStorageSync('selectedAddress');
      const rows = addresses.rows || [];
      const address = rows.find(item => item.id === stored?.id) || rows.find(item => item.isDefault) || rows[0] || null;
      if (address) wx.setStorageSync('selectedAddress', address);

      const latest = new Map();
      const merchantMinimums = new Map();
      for (const merchant of catalog.merchants || []) {
        merchantMinimums.set(merchant.id, merchant.minimumOrderCents || 0);
        for (const product of merchant.products) latest.set(product.id, { product, merchant });
      }
      this.items = cart.read().map(item => {
        const entry = latest.get(item.productId);
        const unavailable = !entry || !entry.merchant.open || entry.product.availableStock < item.quantity;
        return {
          ...item, unavailable,
          priceCents: entry?.product.priceCents ?? item.priceCents,
          name: entry?.product.name ?? item.name,
          photoPath: entry?.product.photoPath ?? item.photoPath
        };
      });
      const totals = cart.totals(this.items, config.deliveryFeeCents);
      const groups = cart.groups(this.items).map(group => {
        const amounts = cart.totals(group.items, config.deliveryFeeCents);
        const minimum = merchantMinimums.get(group.merchantId) || 0;
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
        groups, totals, address, config, loading: false,
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
  openCart() { return openCart(); },

  async submit() {
    if (this.data.submitting || this.data.loading || this.data.unavailable || this.data.belowMinimum || !this.data.paymentEnabled) return;
    if (!this.data.address) return this.chooseAddress();
    const items = this.items.map(({ productId, quantity }) => ({ productId, quantity }));
    if (!items.length) return;
    this.setData({ submitting: true, submitError: '' });

    const addressId = this.data.address.id;
    const expectedTotalCents = this.data.totals.totalCents;
    const fingerprint = JSON.stringify([this.user.id, addressId, expectedTotalCents, items]);
    let attempt = wx.getStorageSync('checkoutAttempt');
    // 请求超时后使用原请求编号重试，避免同一篮餐品生成两笔待付款订单。
    if (attempt?.fingerprint !== fingerprint) {
      attempt = { fingerprint, requestKey: `mini_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` };
      wx.setStorageSync('checkoutAttempt', attempt);
    }

    try {
      const { data } = await api.post('/checkout', { addressId, requestKey: attempt.requestKey, expectedTotalCents, items });
      const payment = data.payment;
      if (payment.status === '已关闭') {
        wx.removeStorageSync('checkoutAttempt');
        this.setData({ submitError: '上一笔支付单已关闭，请重新提交。' });
        return;
      }
      wx.setStorageSync('checkoutAttempt', { ...attempt, paymentId: payment.id });
      wx.setStorageSync('cartPayment:' + payment.id, {
        userId: this.user.id,
        items: this.items.map(({ productId, quantity, lineKey }) => ({ productId, quantity, lineKey: lineKey || '' }))
      });
      const page = payment.status === '支付成功' ? 'payment-result' : 'payment';
      wx.navigateTo({ url: `/pages/${page}/index?id=${encodeURIComponent(payment.id)}` });
    } catch (error) {
      this.setData({ submitError: error.message || '提交结果暂未确认，请重试' });
      if (error.statusCode === 409) {
        wx.removeStorageSync('checkoutAttempt');
      }
    } finally {
      this.setData({ submitting: false });
    }
  }
});
