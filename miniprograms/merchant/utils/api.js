const { baseUrl } = require('./config');
const sessionKey = 'merchant-session';

function token() {
  return wx.getStorageSync(sessionKey) || '';
}

function request(path, data, method = 'GET') {
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseUrl() + path,
      method,
      data,
      timeout: 12000,
      header: token() ? { Authorization: 'Bearer ' + token() } : {},
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data);
          return;
        }
        if (res.statusCode === 401) wx.removeStorageSync(sessionKey);
        const error = new Error((res.data && res.data.message) || '服务暂不可用，请稍后重试');
        error.status = res.statusCode;
        reject(error);
      },
      fail() {
        reject(new Error('连接商户服务失败，请检查网络后重试'));
      }
    });
  });
}

async function login() {
  const code = await new Promise((resolve, reject) => {
    wx.login({
      success: result => result.code ? resolve(result.code) : reject(new Error('微信登录未返回凭证')),
      fail: () => reject(new Error('微信登录失败，请重试'))
    });
  });
  const session = await request('/auth/login', { code }, 'POST');
  wx.setStorageSync(sessionKey, session.token);
}

async function logout() {
  if (token()) await request('/auth/logout', {}, 'POST');
  wx.removeStorageSync(sessionKey);
}

function photo() {
  return new Promise((resolve, reject) => {
    // 图片走临时文件，避免把 Base64 原图放进小程序视图数据。
    wx.downloadFile({
      url: baseUrl() + '/profile/photo',
      header: { Authorization: 'Bearer ' + token() },
      timeout: 12000,
      success(res) {
        if (res.statusCode === 200) resolve(res.tempFilePath);
        else reject(new Error('店铺图片暂不可用'));
      },
      fail: () => reject(new Error('店铺图片加载失败'))
    });
  });
}

module.exports = { request, login, logout, token, photo };
