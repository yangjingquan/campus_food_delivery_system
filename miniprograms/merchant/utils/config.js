// 开发版走本机网关；体验版及正式版使用微信后台已登记的 HTTPS 域名。
const developOrigin = 'http://127.0.0.1:8082';
const releaseOrigin = 'https://cfdapi.nexbyte.top';

function baseUrl() {
  const { miniProgram } = wx.getAccountInfoSync();
  const origin = miniProgram.envVersion === 'develop' ? developOrigin : releaseOrigin;
  if (!origin) throw new Error('商户接口域名尚未配置');
  return origin + '/api/merchant/v1';
}

module.exports = { baseUrl, templateId: '' };
