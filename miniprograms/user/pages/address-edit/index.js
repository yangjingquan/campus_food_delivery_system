const api = require('../../utils/api');

Page({
  data: { name: '', phone: '', building: '', room: '', isDefault: false, error: '', saving: false },

  onLoad(query) {
    this.addressId = query.id || '';
    wx.setNavigationBarTitle({ title: this.addressId ? '编辑收餐地址' : '新增收餐地址' });
    this.load();
  },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      if (this.addressId) {
        const { data } = await api.get('/addresses');
        const address = (data.rows || []).find(item => item.id === this.addressId);
        if (!address) throw new Error('该收餐地址已删除或不可用');
        this.setData(address);
      }
    } catch (error) {
      this.setData({ loadError: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },

  input(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value, error: '' }); },
  toggleDefault(event) { this.setData({ isDefault: event.detail.value }); },

  async save() {
    if (this.data.saving || this.data.loading || this.data.loadError) return;
    const { name, phone, building, room, isDefault } = this.data;
    const body = { name: name.trim(), phone: phone.trim(), building: building.trim(), room: room.trim(), isDefault };
    if (!body.name) return this.setData({ error: '请填写收餐人姓名。' });
    if (!/^1[3-9]\d{9}$/.test(body.phone)) return this.setData({ error: '请填写有效的 11 位手机号。' });
    if (!body.building || !body.room) return this.setData({ error: '请填写宿舍楼和寝室号。' });

    this.setData({ saving: true, error: '' });
    try {
      const { data } = this.addressId
        ? await api.put(`/addresses/${this.addressId}`, body)
        : await api.post('/addresses', body);
      const current = wx.getStorageSync('selectedAddress');
      if (isDefault || !current || current.id === data.id) wx.setStorageSync('selectedAddress', data);
      wx.navigateBack({ delta: 1 });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ saving: false });
    }
  }
});
