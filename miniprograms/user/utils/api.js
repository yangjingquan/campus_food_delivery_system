const BASE_URL = wx.getStorageSync('userApiBaseUrl') || 'https://cfdapi.nexbyte.top/api/user/v1';

function request(method, path, data, options = {}) {
  return new Promise((resolve, reject) => {
    const token = options.auth === false ? '' : wx.getStorageSync('userToken');
    wx.request({
      url: BASE_URL + path,
      method,
      timeout: 12000,
      data,
      header: { 'content-type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) resolve(response);
        else reject(Object.assign(new Error(response.data?.message || '请求暂时无法完成'), { statusCode: response.statusCode, response }));
      },
      fail() { reject(new Error('网络连接暂不可用，请稍后重试')); }
    });
  });
}

module.exports = {
  get: (path, options) => request('GET', path, undefined, options),
  post: (path, data, options) => request('POST', path, data, options),
  put: (path, data, options) => request('PUT', path, data, options),
  delete: (path, options) => request('DELETE', path, undefined, options),
  baseUrl: BASE_URL
};
