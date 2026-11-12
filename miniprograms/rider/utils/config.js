// 微信开发者工具联调时，将 develop 环境改为本地网关地址。
const origins = {
  develop: 'https://cfdapi.nexbyte.top',
  trial: 'https://cfdapi.nexbyte.top',
  release: 'https://cfdapi.nexbyte.top'
};

function baseUrl() {
  const version = wx.getAccountInfoSync().miniProgram.envVersion;
  return (origins[version] || origins.develop) + '/api/rider/v1';
}

module.exports = { baseUrl };
