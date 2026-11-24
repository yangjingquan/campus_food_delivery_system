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
  return withToken(bearer => send(method, path, data, bearer));
}

async function withToken(action) {
  const bearer = await token();
  try {
    return await action(bearer);
  } catch (error) {
    if (error.status !== 401) throw error;
    // 服务在业务处理前拒绝过期会话，更新凭证后可安全重发。
    if (wx.getStorageSync(tokenKey) === bearer) wx.removeStorageSync(tokenKey);
    return action(await token());
  }
}

async function uploadImage(path, filePath, name, formData) {
  const upload = async bearer => unwrap(await wxCall('uploadFile', {
    url: baseUrl() + path,
    filePath,
    name,
    ...(formData ? { formData } : {}),
    header: { Authorization: 'Bearer ' + bearer },
    timeout: 20000
  }).catch(() => { throw new Error('上传失败，请检查网络后重试'); }));
  return withToken(upload);
}

function downloadDeliveryPhoto(orderId) {
  return withToken(async bearer => {
    const response = await wxCall('downloadFile', {
      url: baseUrl() + '/orders/' + encodeURIComponent(orderId) + '/delivery-photo',
      header: { Authorization: 'Bearer ' + bearer },
      timeout: 12000
    });
    if (response.statusCode === 200 && response.tempFilePath) return response.tempFilePath;
    throw Object.assign(new Error('送达照片加载失败'), { status: response.statusCode });
  });
}

function apply(form, filePath) {
  return uploadImage('/profile/application', filePath, 'studentCard', form);
}

function completeDelivery(orderId, filePath) {
  return uploadImage('/orders/' + encodeURIComponent(orderId) + '/complete', filePath, 'photo');
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

module.exports = { request, wxCall, apply, completeDelivery, downloadDeliveryPhoto, logout };
