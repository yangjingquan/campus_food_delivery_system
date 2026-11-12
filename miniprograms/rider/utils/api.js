const { baseUrl } = require('./config');
const tokenKey = 'campus-rider-token';
let loginInFlight;

function wxCall(method, options = {}) {
  return new Promise((resolve, reject) => {
    wx[method]({ ...options, success: resolve, fail: reject });
  });
}

function unwrap(response) {
  let body = response.data;
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      body = {};
    }
  }
  if (response.statusCode >= 200 && response.statusCode < 300) return body;
  const error = new Error(body?.message || '服务暂不可用，请稍后再试');
  error.status = response.statusCode;
  throw error;
}

async function send(method, path, data, token) {
  const response = await wxCall('request', {
    method,
    url: baseUrl() + path,
    header: token ? { Authorization: 'Bearer ' + token } : {},
    data,
    timeout: 12000
  }).catch(() => { throw new Error('网络连接失败，请检查网络后重试'); });
  return unwrap(response);
}

async function token() {
  const saved = wx.getStorageSync(tokenKey);
  if (saved) return saved;
  if (!loginInFlight) {
    loginInFlight = wxCall('login')
      .catch(() => { throw new Error('微信登录失败，请稍后再试'); })
      .then(result => send('POST', '/auth/login', { code: result.code }))
      .then(result => {
        wx.setStorageSync(tokenKey, result.token);
        return result.token;
      })
      .finally(() => { loginInFlight = null; });
  }
  return loginInFlight;
}

async function request(path, method = 'GET', data) {
  const bearer = await token();
  try {
    return await send(method, path, data, bearer);
  } catch (error) {
    if (error.status !== 401) throw error;
    // 服务在业务处理前拒绝过期会话，更新凭证后可安全重发。
    if (wx.getStorageSync(tokenKey) === bearer) wx.removeStorageSync(tokenKey);
    return send(method, path, data, await token());
  }
}

async function apply(form, filePath) {
  const upload = async bearer => unwrap(await wxCall('uploadFile', {
    url: baseUrl() + '/profile/application',
    filePath,
    name: 'studentCard',
    formData: form,
    header: { Authorization: 'Bearer ' + bearer },
    timeout: 20000
  }).catch(() => { throw new Error('上传失败，请检查网络后重试'); }));
  const bearer = await token();
  try {
    return await upload(bearer);
  } catch (error) {
    if (error.status !== 401) throw error;
    if (wx.getStorageSync(tokenKey) === bearer) wx.removeStorageSync(tokenKey);
    return upload(await token());
  }
}

async function logout() {
  const bearer = wx.getStorageSync(tokenKey);
  if (bearer) {
    try {
      await send('POST', '/auth/logout', undefined, bearer);
    } finally {
      wx.removeStorageSync(tokenKey);
    }
  }
}

module.exports = { request, wxCall, apply, logout };
