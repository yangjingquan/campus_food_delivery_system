const api = require('../../utils/api');
const { money, time } = require('../../utils/display');
const { availability } = require('../../utils/aftersale');

const stages = ['AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_RIDER', 'DELIVERING', 'COMPLETED'];
const steps = ['待接单', '出餐中', '已到点', '配送中', '已完成'];
const pickupStages = ['AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_PICKUP', 'COMPLETED'];
const statusNotes = {
  PENDING_PAYMENT: '付款确认后，商户将收到订单。',
  AWAITING_MERCHANT: '订单已付款，等待商户接单。',
  PREPARING_FOOD: '商户正在准备你的餐品。',
  'AWAITING_RIDER': '餐品已到校园暂存点，等待骑手领取。',
  DELIVERING: '骑手已领取餐品，正为你配送。',
  COMPLETED: '餐品已送达。',
  CLOSED: '订单已结束。若申请过售后，可查看处理记录。'
};

Page({
  data: {
    order: null, items: [], steps, stepIndex: -1, aftersales: [], loading: true, loadError: '',
    showDeliveryPhoto: false, photoPath: '', photoLoadError: false
  },

  onLoad(query) { this.orderId = query.id; },
  onShow() {
    this.refreshedAfterDelivery = false;
    this.load();
  },
  onHide() {
    this.orderRequest = null;
    this.photoRequest = null;
  },
  onUnload() { this.onHide(); },

  async load() {
    const request = {};
    this.orderRequest = request;
    this.photoRequest = null;
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      if (this.orderRequest !== request) return;
      const token = wx.getStorageSync('userToken');
      const { data } = await api.get(`/orders/${this.orderId}`);
      if (this.orderRequest !== request) return;
      if (wx.getStorageSync('userToken') !== token) throw new Error('登录状态已变更，请重新加载。');
      const pickup = data.fulfillmentType === 'pickup';
      const showDeliveryPhoto = !pickup && data.fulfillStatus === 'COMPLETED' && !!data.deliveryPhotoAvailable;
      const cachedPhoto = this.deliveryPhoto;
      const photoPath = showDeliveryPhoto && cachedPhoto && cachedPhoto.orderId === data.id && cachedPhoto.token === token
        ? cachedPhoto.path : '';
      if (!photoPath) this.deliveryPhoto = null;
      const phone = String(data.phone ?? '').trim().match(/^(\d{3})[\d*]{4}(\d{4})$/);
      const items = (data.items || []).map(item => ({ ...item, amountText: money(item.amountCents) }));
      const aftersales = (data.aftersales || []).map(item => ({ ...item, amountText: money(item.amountCents) }));
      this.setData({
        order: { ...data, phone: phone ? `${phone[1]}****${phone[2]}` : '' },
        items, aftersales, pickup, loading: false, showDeliveryPhoto, photoPath, photoLoadError: false,
        steps: pickup ? ['待接单', '出餐中', '待自取', '已完成'] : steps,
        stepIndex: (pickup ? pickupStages : stages).indexOf(data.fulfillStatus),
        statusNote: pickup && data.fulfillStatus === 'AWAITING_PICKUP' ? '餐品已备好，请凭取餐号到店领取。'
          : pickup && data.fulfillStatus === 'COMPLETED' ? '商户已确认取餐。' : statusNotes[data.fulfillStatus] || '',
        paidAtText: time(data.paidAt),
        refund: availability(data), amountText: money(data.paidCents), foodText: money(data.foodCents),
        feeText: money(data.deliveryFeeCents), refundedText: money(data.refundedCents)
      });
      if (showDeliveryPhoto && !photoPath) await this.loadDeliveryPhoto();
    } catch (error) {
      if (this.orderRequest === request) this.setData({ loading: false, loadError: error.message });
    }
  },

  refreshAfterDelivery() {
    if (this.refreshedAfterDelivery) return;
    this.refreshedAfterDelivery = true;
    return this.load();
  },

  async loadDeliveryPhoto() {
    const { order, showDeliveryPhoto, photoPath } = this.data;
    if (!showDeliveryPhoto || photoPath || this.photoRequest) return;
    const token = wx.getStorageSync('userToken');
    if (!token) {
      this.setData({ photoLoadError: true });
      return;
    }
    this.setData({ photoLoadError: false });
    const request = this.photoRequest = api.downloadDeliveryPhoto(order.id, token);
    try {
      const path = await request;
      if (this.photoRequest !== request || wx.getStorageSync('userToken') !== token) return;
      this.deliveryPhoto = { orderId: order.id, token, path };
      this.setData({ photoPath: path });
    } catch (_) {
      // 下载失败时保留重试入口。
    } finally {
      if (this.photoRequest === request) {
        this.photoRequest = null;
        if (!this.data.photoPath) this.setData({ photoLoadError: true });
      }
    }
  },

  deliveryPhotoError(event) {
    if (event.currentTarget.dataset.path !== this.data.photoPath) return;
    this.deliveryPhoto = null;
    this.setData({ photoPath: '', photoLoadError: true });
  },

  previewDeliveryPhoto() {
    const { photoPath } = this.data;
    if (!photoPath || this.deliveryPhoto?.token !== wx.getStorageSync('userToken')) return;
    wx.previewImage({
      current: photoPath, urls: [photoPath],
      fail: () => wx.showToast({ title: '送达照片暂时无法打开，请重试', icon: 'none' })
    });
  },

  refund() { wx.navigateTo({ url: `/pages/refund/index?id=${this.orderId}` }); },
  pay() { wx.navigateTo({ url: `/pages/payment/index?id=${this.data.order.paymentId}` }); },
  afterSale(event) { wx.navigateTo({ url: `/pages/aftersale/index?id=${event.currentTarget.dataset.id}` }); },
  copy(event) { wx.setClipboardData({ data: event.currentTarget.dataset.value }); }
});
