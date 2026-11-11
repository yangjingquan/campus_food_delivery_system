const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  onboarding: ui.onboarding,
  async refresh() {
    this.setData({ loading: true, error: '', blocked: '', store: null, revision: null, loggedIn: !!api.token() });
    try {
      if (!api.token()) {
        this.setData({ blocked: '请先使用微信登录' });
        return;
      }
      const profile = await api.request('/profile');
      this.setData({ ...profile, minimumOrder: (Number(profile.store?.minimumOrderCents || 0) / 100).toFixed(2) });
    } catch (error) {
      this.setData({ error: error.message, loggedIn: !!api.token() });
    } finally {
      this.setData({ loading: false });
    }
  },
  shop() { ui.go('/pages/onboarding/index?edit=1'); },
  devices() { ui.go('/pages/devices/index'); },
  inputMinimumOrder(event) { this.setData({ minimumOrder: event.detail.value, minimumOrderError: '' }); },
  async saveMinimumOrder() {
    if (this.data.saving) return;
    this.setData({ saving: true, minimumOrderError: '' });
    try {
      const minimumOrderCents = ui.cents(this.data.minimumOrder, true);
      await api.request('/profile/minimum-order', { minimumOrderCents }, 'PUT');
      this.setData({ minimumOrder: (minimumOrderCents / 100).toFixed(2) });
      wx.showToast({ title: '起送价已保存', icon: 'success' });
    } catch (error) {
      this.setData({ minimumOrderError: error.message });
    } finally {
      this.setData({ saving: false });
    }
  },
  business() {
    ui.action(this, async () => {
      const open = this.data.store.businessStatus !== '营业中';
      if (!await ui.confirm(open ? '开始营业' : '确认打烊', open ? '营业后用户可下单。' : '打烊后暂停新单，已有订单仍可处理。')) return;
      await api.request('/profile/business-status', { open }, 'PUT');
      await this.refresh();
    });
  },
  logout() {
    ui.action(this, async () => {
      if (!await ui.confirm('退出登录', '下次打开工作台时需重新登录。')) return;
      await api.logout();
      await this.refresh();
    });
  }
});
