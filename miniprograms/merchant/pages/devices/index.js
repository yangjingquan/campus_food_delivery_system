const api = require('../../utils/api');
const ui = require('../../utils/ui');
const { templateId } = require('../../utils/config');

Page({
  data: { canSubscribe: !!templateId, loading: true },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      this.setData({ printer: await api.request('/printer') });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  bindPrinter() {
    ui.action(this, async () => {
      const result = await api.request('/printer/bind', {}, 'POST');
      this.setData({ printerMessage: result.message });
      await this.refresh();
    });
  },
  subscribe() {
    if (!templateId) return;
    wx.requestSubscribeMessage({
      tmplIds: [templateId],
      success: result => this.setData({ message: result[templateId] === 'accept' ? '已授权接收该模板消息' : '未授权该模板消息' }),
      fail: () => this.setData({ message: '订阅申请未完成，请稍后再试' })
    });
  }
});
