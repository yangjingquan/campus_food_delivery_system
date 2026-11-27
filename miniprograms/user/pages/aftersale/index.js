const api = require('../../utils/api');
const { money, time } = require('../../utils/display');

const hints = {
  AWAITING_MERCHANT_DECISION: '商户将在规定时限内处理，超时由平台介入。',
  REFUND_REJECTED: '可申请平台介入，或结束这次售后。',
  REFUND_QUEUED: '同一支付单需间隔提交退款，请等待处理。',
  REFUND_PROCESSING: '退款申请正在处理，确认到账前仍可查看进度。',
  REFUND_FAILED: '退款渠道明确失败，平台将核实后续处理方式。',
  REFUND_VERIFYING: '平台正在与退款渠道核对结果，请勿重复申请。',
  REFUND_SUCCEEDED: '退款已确认，相关商户订单已关闭。',
  OFFLINE_RESOLVED: '平台已确认线下退款，原线上退款记录仍可查看。'
};

Page({
  data: { item: null, events: [], photoPath: '', photoError: false, loading: true, loadError: '', working: false },

  onLoad(query) { this.refundId = query.id; },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const token = wx.getStorageSync('userToken');
      const { data } = await api.get(`/aftersales/${this.refundId}`);
      const events = (data.events || []).map((event, index) => ({
        ...event, key: index, timeText: time(event.createdAt)
      }));
      this.setData({
        item: data, events, photoPath: '', photoError: false, loading: false,
        amountText: money(data.amountCents),
        deliveryRefundText: data.deliveryRefundCents ? money(data.deliveryRefundCents) : '',
        createdAtText: time(data.createdAt),
        stateHint: hints[data.status] || '请查看处理记录。',
        canIntervene: ['REFUND_REJECTED', 'AWAITING_MERCHANT_DECISION'].includes(data.status)
          || (data.status === 'REFUND_VERIFYING' && !data.platformIntervened),
        canEnd: data.status === 'REFUND_REJECTED'
      });
      if (data.hasPhoto && token) this.loadPhoto();
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  async loadPhoto() {
    const token = wx.getStorageSync('userToken');
    if (!token || !this.data.item?.hasPhoto) return;
    this.setData({ photoError: false });
    try {
      const photoPath = await api.downloadAftersalePhoto(this.refundId, token);
      this.setData({ photoPath });
    } catch (_) {
      this.setData({ photoError: true });
    }
  },

  previewPhoto() {
    if (this.data.photoPath) wx.previewImage({ current: this.data.photoPath, urls: [this.data.photoPath] });
  },

  intervene() { this.act('intervene'); },
  end() { this.act('close'); },

  async act(action) {
    if (this.data.working) return;
    this.setData({ working: true, actionError: '' });
    try {
      await api.post(`/aftersales/${this.refundId}/${action}`, {});
      await this.load();
    } catch (error) {
      this.setData({ actionError: error.message });
    } finally {
      this.setData({ working: false });
    }
  },

  back() { wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/orders/index' }) }); }
});
