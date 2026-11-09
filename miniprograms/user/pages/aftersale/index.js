const api = require('../../utils/api');
const { money, time } = require('../../utils/display');

const hints = {
  待商户处理: '商户将在规定时限内处理，超时由平台介入。',
  拒绝退款: '可申请平台介入，或结束这次售后。',
  等待退款提交: '同一支付单需间隔提交退款，请等待处理。',
  退款处理中: '退款申请正在处理，确认到账前仍可查看进度。',
  退款失败: '退款渠道明确失败，平台将核实后续处理方式。',
  退款结果核实中: '平台正在与退款渠道核对结果，请勿重复申请。',
  退款成功: '退款已确认，相关商户订单已关闭。',
  线下已处理: '平台已确认线下退款，原线上退款记录仍可查看。'
};

Page({
  data: { item: null, events: [], loading: true, loadError: '', working: false },

  onLoad(query) { this.refundId = query.id; },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/aftersales/${this.refundId}`);
      const events = (data.events || []).map((event, index) => ({
        ...event, key: index, timeText: time(event.createdAt)
      }));
      this.setData({
        item: data, events, loading: false, amountText: money(data.amountCents), createdAtText: time(data.createdAt),
        stateHint: hints[data.status] || '请查看处理记录。',
        canIntervene: ['拒绝退款', '待商户处理'].includes(data.status)
          || (data.status === '退款结果核实中' && !data.platformIntervened),
        canEnd: data.status === '拒绝退款'
      });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
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
