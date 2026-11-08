const api = require('../../utils/api');
Page({
  data: { id: '', name: '', phone: '', building: '', room: '', isDefault: false, error: '', saving: false },
  async onLoad(query) {
    this.addressId = query.id || '';
    if (!this.addressId) return;
    try {
      await getApp().ensureLogin();
      const { data } = await api.get('/addresses');
      const address = (data.rows || []).find(item => item.id === this.addressId);
      if (address) this.setData({ id: address.id, name: address.name, phone: address.phone, building: address.building, room: address.room, isDefault: address.isDefault });
    } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
  },
  input(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value, error: '' }); },
  toggleDefault(event) { this.setData({ isDefault: event.detail.value }); },
  async save() {
    if (this.data.saving) return;
    this.setData({ saving: true, error: '' });
    const body = { name: this.data.name.trim(), phone: this.data.phone.trim(), building: this.data.building.trim(), room: this.data.room.trim(), isDefault: this.data.isDefault };
    try {
      const response = this.addressId ? await api.put(`/addresses/${this.addressId}`, body) : await api.post('/addresses', body);
      if (this.data.isDefault || !wx.getStorageSync('selectedAddress')) wx.setStorageSync('selectedAddress', response.data);
      wx.navigateBack({ delta: 1 });
    } catch (error) { this.setData({ saving: false, error: error.message || '请检查地址信息。' }); }
  }
});
