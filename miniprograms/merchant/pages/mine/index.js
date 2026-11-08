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
      this.setData(await api.request('/profile'));
    } catch (error) {
      this.setData({ error: error.message, loggedIn: !!api.token() });
    } finally {
      this.setData({ loading: false });
    }
  },
  shop() { ui.go('/pages/onboarding/index?edit=1'); },
  devices() { ui.go('/pages/devices/index'); },
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
