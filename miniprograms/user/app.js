const api = require('./utils/api');

App({
  globalData: { config: null, user: null },

  onLaunch() {
    this.getConfig().catch(() => {});
  },

  async getConfig(refresh = false) {
    if (this.globalData.config && !refresh) return this.globalData.config;
    if (!this.configRequest) {
      this.configRequest = api.get('/config', { auth: false }).then(({ data }) => {
        this.globalData.config = data;
        return data;
      }).finally(() => { this.configRequest = null; });
    }
    return this.configRequest;
  },

  async ensureLogin() {
    if (!this.loginRequest) {
      this.loginRequest = this.login().finally(() => { this.loginRequest = null; });
    }
    return this.loginRequest;
  },

  async login() {
    if (wx.getStorageSync('userToken')) {
      try {
        const { data } = await api.get('/me');
        this.globalData.user = data;
        return data;
      } catch (error) {
        // 网络波动时保留会话，只有服务端确认失效才重新登录。
        if (error.statusCode !== 401) throw error;
        this.clearSession();
      }
    }

    const config = await this.getConfig();
    let code;
    if (config.mockLogin) {
      code = wx.getStorageSync('localMockLoginCode');
      if (!code) {
        code = `local_${Date.now()}_${Math.random().toString(36).slice(2, 12)}`;
        wx.setStorageSync('localMockLoginCode', code);
      }
    } else {
      const login = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: reject }));
      if (!login.code) throw new Error('未获取到微信登录凭证，请重试');
      code = login.code;
    }
    const { data } = await api.post('/auth/wechat', { code }, { auth: false });
    wx.setStorageSync('userToken', data.token);
    this.globalData.user = data.user;
    return data.user;
  },

  clearSession() {
    wx.removeStorageSync('userToken');
    wx.removeStorageSync('selectedAddress');
    wx.removeStorageSync('checkoutAttempt');
    this.globalData.user = null;
  }
});
