const api = require('../../utils/api');
const { money, time } = require('../../utils/display');
const { availability } = require('../../utils/aftersale');

const stages = ['待商户接单', '商户出餐中', '已到暂存点 / 待领取', '配送中', '已完成'];
const steps = ['待接单', '出餐中', '已到点', '配送中', '已完成'];
const statusNotes = {
  待支付: '付款确认后，商户将收到订单。',
  待商户接单: '订单已付款，等待商户接单。',
  商户出餐中: '商户正在准备你的餐品。',
  '已到暂存点 / 待领取': '餐品已到校园暂存点，等待骑手领取。',
  配送中: '骑手已领取餐品，正为你配送。',
  已完成: '餐品已送达。',
  已关闭: '订单已结束。若申请过售后，可查看处理记录。'
};

Page({
  data: { order: null, items: [], steps, stepIndex: -1, aftersales: [], loading: true, loadError: '' },

  onLoad(query) { this.orderId = query.id; },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}`);
      const items = (data.items || []).map(item => ({ ...item, amountText: money(item.amountCents) }));
      const aftersales = (data.aftersales || []).map(item => ({ ...item, amountText: money(item.amountCents) }));
      this.setData({
        order: data, items, aftersales, stepIndex: stages.indexOf(data.fulfillStatus), loading: false,
        statusNote: statusNotes[data.fulfillStatus] || '', paidAtText: time(data.paidAt),
        refund: availability(data), amountText: money(data.paidCents), foodText: money(data.foodCents),
        feeText: money(data.deliveryFeeCents), refundedText: money(data.refundedCents)
      });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  map() { wx.navigateTo({ url: `/pages/map/index?id=${this.orderId}` }); },
  refund() { wx.navigateTo({ url: `/pages/refund/index?id=${this.orderId}` }); },
  pay() { wx.navigateTo({ url: `/pages/payment/index?id=${this.data.order.paymentId}` }); },
  afterSale(event) { wx.navigateTo({ url: `/pages/aftersale/index?id=${event.currentTarget.dataset.id}` }); },
  copy(event) { wx.setClipboardData({ data: event.currentTarget.dataset.value }); }
});
