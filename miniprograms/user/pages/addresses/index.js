const api = require('../../utils/api');

Page({
  data: { rows: [], choose: false, selectedId: '', loading: true, loadError: '' },

  onLoad(query) { this.setData({ choose: query.choose === '1' }); },
  onShow() { this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get('/addresses');
      const rows = data.rows || [];
      const current = wx.getStorageSync('selectedAddress');
      const selected = rows.find(item => item.id === current?.id) || rows.find(item => item.isDefault);
      if (selected) wx.setStorageSync('selectedAddress', selected);
      else wx.removeStorageSync('selectedAddress');
      this.setData({ rows, selectedId: selected?.id || '', loading: false });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  add() { wx.navigateTo({ url: '/pages/address-edit/index' }); },
  edit(event) { wx.navigateTo({ url: `/pages/address-edit/index?id=${event.currentTarget.dataset.id}` }); },

  select(event) {
    const address = this.data.rows.find(item => item.id === event.currentTarget.dataset.id);
    if (!address) return;
    wx.setStorageSync('selectedAddress', address);
    this.setData({ selectedId: address.id });
    if (this.data.choose) wx.navigateBack({ delta: 1 });
  },

  remove(event) {
    if (this.removing) return;
    const id = event.currentTarget.dataset.id;
    wx.showModal({
      title: '删除收餐地址',
      content: '确定删除这条地址吗？',
      success: async ({ confirm }) => {
        if (!confirm || this.removing) return;
        this.removing = true;
        try {
          await api.delete(`/addresses/${id}`);
          await this.load();
        } catch (error) {
          wx.showToast({ title: error.message, icon: 'none' });
        } finally {
          this.removing = false;
        }
      }
    });
  }
});
