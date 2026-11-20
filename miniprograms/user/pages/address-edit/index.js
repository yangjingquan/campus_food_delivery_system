const api = require('../../utils/api');
const { hasCoordinates } = require('../../utils/coordinates');

Page({
  data: {
    name: '', phone: '', building: '', room: '', isDefault: false, error: '', saving: false,
    choosingLocation: false, latitude: null, longitude: null, hasLocation: false,
    locationName: '', locationPermissionDenied: false
  },

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
        this.setData({ ...address, hasLocation: hasCoordinates(address) });
      }
    } catch (error) {
      this.setData({ loadError: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },

  input(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    const patch = { [field]: value, error: '' };
    // 更换楼栋后重新确认位置，避免文字地址和地图位置不一致。
    if (field === 'building' && value.trim() !== this.data.building.trim()) {
      Object.assign(patch, { latitude: null, longitude: null, hasLocation: false, locationName: '' });
    }
    this.setData(patch);
  },

  chooseLocation() {
    if (this.data.choosingLocation || this.data.saving) return;
    this.setData({ choosingLocation: true, error: '', locationPermissionDenied: false });
    if (typeof wx.requirePrivacyAuthorize !== 'function') {
      this.openLocationPicker();
      return;
    }
    wx.requirePrivacyAuthorize({
      success: () => this.openLocationPicker(),
      fail: error => {
        this.handleLocationError(error, true);
        this.setData({ choosingLocation: false });
      }
    });
  },

  openLocationPicker() {
    const position = hasCoordinates(this.data)
      ? { latitude: Number(this.data.latitude), longitude: Number(this.data.longitude) } : {};
    wx.chooseLocation({
      ...position,
      success: result => {
        if (!hasCoordinates(result)) {
          this.setData({ error: '未获取到有效位置，请重新在地图中选点。' });
          return;
        }
        this.setData({
          latitude: result.latitude, longitude: result.longitude, hasLocation: true,
          locationName: result.name || result.address || '已设置收餐位置',
          building: this.data.building.trim() || (result.name || '').slice(0, 80), error: ''
        });
      },
      fail: error => this.handleLocationError(error),
      complete: () => this.setData({ choosingLocation: false })
    });
  },

  handleLocationError({ errMsg = '', errno }, checkingPrivacy = false) {
    if (/cancel/i.test(errMsg)) return;
    console.warn('[收餐地址地图选点]', errno, errMsg);
    if (Number(errno) === 112 || /scope is not declared in the privacy agreement/i.test(errMsg)) {
      this.setData({ error: '小程序尚未开通地图选点，请联系平台客服。' });
      return;
    }
    if (Number(errno) === 104 || /privacy.*(not authoriz|denied|deny|disagree|reject)/i.test(errMsg)
      || (checkingPrivacy && /auth deny|auth denied|disagree|reject/i.test(errMsg))) {
      this.setData({ error: '地图选点需要隐私授权。请再次点击地图选点并同意隐私保护指引。' });
      return;
    }
    if (checkingPrivacy) {
      this.setData({ error: '暂时无法确认隐私授权，请稍后重试。' });
      return;
    }
    if (/auth deny|auth denied|authorize|permission/i.test(errMsg) && !/privacy/i.test(errMsg)) {
      this.setData({ locationPermissionDenied: true, error: '位置权限未开启，请在设置中允许位置访问后重试。' });
      return;
    }
    this.setData({ error: '暂时无法选择位置，请检查网络和系统定位服务后重试。' });
  },

  openLocationSettings() {
    wx.openSetting({
      success: () => this.setData({ error: '', locationPermissionDenied: false }),
      fail: () => this.setData({ error: '无法打开设置，请在微信中检查小程序位置权限。' })
    });
  },
  toggleDefault(event) { this.setData({ isDefault: event.detail.value }); },

  async save() {
    if (this.data.saving || this.data.choosingLocation || this.data.loading || this.data.loadError) return;
    const { name, phone, building, room, isDefault, latitude, longitude } = this.data;
    const body = {
      name: name.trim(), phone: phone.trim(), building: building.trim(), room: room.trim(),
      isDefault, latitude, longitude
    };
    if (!body.name) return this.setData({ error: '请填写收餐人姓名。' });
    if (!/^1[3-9]\d{9}$/.test(body.phone)) return this.setData({ error: '请填写有效的 11 位手机号。' });
    if (!body.building || !body.room) return this.setData({ error: '请填写宿舍楼和寝室号。' });

    if (!hasCoordinates(body)) return this.setData({ error: '请在地图中设置收餐位置，方便骑手准确送达。' });

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
