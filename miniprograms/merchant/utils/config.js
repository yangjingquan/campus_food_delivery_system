// 手动切换接口环境，修改后在微信开发者工具重新编译。
const apiEnvironment = 'production'; // development 或 production

const apiOrigins = {
  development: 'http://127.0.0.1:8082',
  production: 'https://cfdapi.nexbyte.top'
};

function baseUrl() {
  if (!Object.prototype.hasOwnProperty.call(apiOrigins, apiEnvironment)) {
    throw new Error('商户接口环境配置无效');
  }
  return apiOrigins[apiEnvironment] + '/api/merchant/v1';
}

module.exports = { apiEnvironment, baseUrl, templateId: '' };
