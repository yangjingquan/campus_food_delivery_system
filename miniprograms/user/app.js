const api = require('./utils/api');

App({
  globalData: { config: null, configPromise: null, user: null },
  onLaunch() {
    this.globalData.configPromise = api.get('/config').then(({ data }) => { this.globalData.config = data; return data; }).catch(() => null);
  },
  async ensureLogin() {
    const cached = wx.getStorageSync('userToken');
    if (cached) {
      try {
        const response = await api.get('/me');
        this.globalData.user = response.data;
        return response.data;
      } catch (_) { wx.removeStorageSync('userToken'); }
    }
    if (!this.globalData.config) {
      await this.globalData.configPromise;
      if (!this.globalData.config) {
        try { const { data } = await api.get('/config'); this.globalData.config = data; } catch (_) {}
      }
    }
    let code;
    if (this.globalData.config?.mockLogin) {
      code = wx.getStorageSync('localMockLoginCode');
      if (!code) { code = `local_${Date.now()}_${Math.random().toString(36).slice(2, 12)}`; wx.setStorageSync('localMockLoginCode', code); }
    } else {
      const login = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: reject }));
      code = login.code;
    }
    const response = await api.post('/auth/wechat', { code }, { auth: false });
    wx.setStorageSync('userToken', response.data.token);
    this.globalData.user = response.data.user;
    return response.data.user;
  }
});
