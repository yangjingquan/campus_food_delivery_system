const { templateId } = require('../../utils/config');

Page({
  data: { canSubscribe: !!templateId },
  onPullDownRefresh() { wx.stopPullDownRefresh(); },
  subscribe() {
    if (!templateId) return;
    wx.requestSubscribeMessage({
      tmplIds: [templateId],
      success: result => this.setData({ message: result[templateId] === 'accept' ? '已授权接收该模板消息' : '未授权该模板消息' }),
      fail: () => this.setData({ message: '订阅申请未完成，请稍后再试' })
    });
  }
});
