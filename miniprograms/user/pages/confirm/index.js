const api = require('../../utils/api');
const cart = require('../../utils/cart');
Page({
  data: { groups: [], totals: {}, address: null, foodText: '¥0.00', feeText: '¥0.00', totalText: '¥0.00', feeCount: 0, config: {} },
  async onShow() { await this.load(); },
  async load() {
    try {
      await getApp().ensureLogin();
      const [addressResult, configResult] = await Promise.all([api.get('/addresses'), api.get('/config')]);
      const config = configResult.data;
      const addresses = addressResult.data.rows || [];
      const storedAddress = wx.getStorageSync('selectedAddress');
      const address = addresses.find(item => item.id === storedAddress?.id) || addresses.find(item => item.isDefault) || addresses[0] || null;
      if (address) wx.setStorageSync('selectedAddress', address);
      const items = cart.read();
      const totals = cart.totals(items, config.deliveryFeeCents);
      const groups = cart.groups(items).map(group => ({
        ...group,
        items: group.items.map(item => ({ ...item, priceText: `¥${(item.priceCents / 100).toFixed(2)}`, amountText: `¥${(item.priceCents * item.quantity / 100).toFixed(2)}` })),
        foodText: `¥${(group.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0) / 100).toFixed(2)}`,
        orderText: `¥${((group.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0) + config.deliveryFeeCents) / 100).toFixed(2)}`
      }));
      this.setData({ groups, totals, address, config, paymentEnabled: config.paymentMode === 'mock', feeCount: totals.merchantCount, foodText: `¥${(totals.foodCents / 100).toFixed(2)}`, feeText: `¥${(totals.deliveryFeeCents / 100).toFixed(2)}`, totalText: `¥${(totals.totalCents / 100).toFixed(2)}` });
    } catch (error) { wx.showToast({ title: error.message || '登录后继续结算', icon: 'none' }); }
  },
  chooseAddress() { wx.navigateTo({ url: '/pages/addresses/index?choose=1' }); },
  async submit() {
    if (!this.data.paymentEnabled) return wx.showModal({ title: '支付暂不可用', content: '当前服务端尚未启用微信支付；订单未提交，也未占用库存。', showCancel: false });
    if (!this.data.address) return this.chooseAddress();
    const items = cart.read();
    if (!items.length) return wx.showToast({ title: '购物车为空', icon: 'none' });
    wx.showLoading({ title: '正在提交' });
    try {
      const key = `mini_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
      const response = await api.post('/checkout', { addressId: this.data.address.id, requestKey: key, items: items.map(item => ({ productId: item.productId, quantity: item.quantity })) });
      wx.setStorageSync('activePaymentId', response.data.payment.id);
      wx.setStorageSync('activeCheckout', response.data);
      wx.hideLoading();
      wx.navigateTo({ url: `/pages/payment/index?id=${response.data.payment.id}` });
    } catch (error) {
      wx.hideLoading();
      wx.showModal({ title: '订单没有提交', content: error.message || '请检查商品、库存和收餐地址后重试。', showCancel: false });
    }
  }
});
