const api = require('../../utils/api');
Page({
  data: { item: null, events: [], amountText: '¥0.00', stateHint: '', canIntervene: false, canEnd: false },
  async onLoad(query) { this.refundId = query.id; await this.load(); },
  async load() {
    try {
      const { data } = await api.get(`/aftersales/${this.refundId}`);
      const hints = { '待商户处理': '商户将在平台规定时限内处理，超时由平台介入。', '拒绝退款': '可联系商户协商，或申请平台介入。', '退款失败': '退款渠道明确失败，平台应线下核实处理，不自动重试。', '退款结果核实中': '最终结果尚未确认，先核实，不重复发起退款。', '退款成功': '实际退款已确认，该商户订单已关闭。', '线下已处理': '平台已确认线下实际退款，原线上失败记录保留。' };
      const events = (data.events || []).map(event => ({ ...event, timeText: this.time(event.createdAt) }));
      this.setData({ item: data, events, amountText: `¥${(data.amountCents / 100).toFixed(2)}`, stateHint: hints[data.status] || '请查看以下处理记录。', canIntervene: ['拒绝退款', '待商户处理', '退款结果核实中'].includes(data.status), canEnd: data.status === '拒绝退款' });
    } catch (error) { wx.showToast({ title: error.message || '售后记录暂不可用', icon: 'none' }); }
  },
  time(value) { const date = typeof value === 'number' ? new Date(value) : new Date(value); return Number.isNaN(date.getTime()) ? '' : date.toLocaleString(); },
  async intervene() { try { await api.post(`/aftersales/${this.refundId}/intervene`, {}); this.load(); } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); } },
  async end() { try { await api.post(`/aftersales/${this.refundId}/close`, {}); this.load(); } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); } },
  contact() { wx.showToast({ title: '请通过平台客服渠道联系商户', icon: 'none' }); },
  back() { wx.navigateBack({ delta: 1 }); }
});
