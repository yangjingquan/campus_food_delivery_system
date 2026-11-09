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

function updateAvatar(avatarPath) {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: BASE_URL + '/me',
      filePath: avatarPath,
      name: 'avatar',
      header: { Authorization: 'Bearer ' + wx.getStorageSync('userToken') },
      timeout: 12000,
      success({ statusCode, data: body }) {
        let data;
        try { data = JSON.parse(body); } catch {}
        if (statusCode === 200 && data?.id) {
          resolve({ data });
          return;
        }
        const message = statusCode === 413 ? '头像大小须在 512 KB 以内' : data?.message || '资料保存失败，请重试';
        reject(Object.assign(new Error(message), { statusCode }));
      },
      fail: () => reject(new Error('头像上传失败，请重试'))
    });
  });
}

function downloadAvatar(token) {
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: BASE_URL + '/me/avatar',
      header: { Authorization: 'Bearer ' + token },
      timeout: 12000,
      success({ statusCode, tempFilePath }) {
        if (statusCode === 200 && tempFilePath) resolve(tempFilePath);
        else reject(new Error('头像加载失败'));
      },
      fail: reject
    });
  });
}

module.exports = {
  get: (path, options) => request('GET', path, undefined, options),
  post: (path, data, options) => request('POST', path, data, options),
  put: (path, data, options) => request('PUT', path, data, options),
  delete: (path, options) => request('DELETE', path, undefined, options),
  updateAvatar,
  downloadAvatar,
  baseUrl: BASE_URL
};
