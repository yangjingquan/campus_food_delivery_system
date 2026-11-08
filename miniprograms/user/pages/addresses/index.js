const api = require('../../utils/api');
Page({
  data: { rows: [], choose: false },
  async onLoad(query) { this.setData({ choose: query.choose === '1' }); },
  async onShow() {
    try { await getApp().ensureLogin(); const { data } = await api.get('/addresses'); this.setData({ rows: data.rows || [] }); }
    catch (error) { wx.showToast({ title: error.message || '请先登录', icon: 'none' }); }
  },
  add() { wx.navigateTo({ url: '/pages/address-edit/index' }); },
  edit(event) { wx.navigateTo({ url: `/pages/address-edit/index?id=${event.currentTarget.dataset.id}` }); },
  async select(event) {
    const address = this.data.rows.find(item => item.id === event.currentTarget.dataset.id);
    if (!address) return;
    wx.setStorageSync('selectedAddress', address);
    if (this.data.choose) wx.navigateBack({ delta: 1 });
    else wx.showToast({ title: '已设为本次收餐地址', icon: 'none' });
  },
  remove(event) {
    const id = event.currentTarget.dataset.id;
    wx.showModal({ title: '删除收餐地址', content: '确定删除这个地址吗？', success: async result => {
      if (!result.confirm) return;
      try { await api.delete(`/addresses/${id}`); const selected = wx.getStorageSync('selectedAddress'); if (selected?.id === id) wx.removeStorageSync('selectedAddress'); this.onShow(); }
      catch (error) { wx.showToast({ title: error.message || '删除失败', icon: 'none' }); }
    } });
  }
});
