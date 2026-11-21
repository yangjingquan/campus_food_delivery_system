const test = require('node:test');
const assert = require('node:assert/strict');
const { openBannerLink } = require('../utils/banner-link');

test('轮播支持页面参数、底部导航和保留完整的 HTTPS 网页链接', () => {
  const opened = [];
  global.wx = {
    navigateTo: ({ url }) => opened.push(['page', url]),
    switchTab: ({ url }) => opened.push(['tab', url]),
    showToast: options => opened.push(['error', options.title])
  };
  openBannerLink({ linkType: 'page', linkUrl: '/pages/store/index?id=M03&from=home' });
  openBannerLink({ linkType: 'page', linkUrl: '/pages/orders/index' });
  const external = 'https://example.com/campaign?target=%2Fwelcome&code=a%2Bb#terms';
  openBannerLink({ linkType: 'external', linkUrl: external });
  openBannerLink({ linkType: 'external', linkUrl: 'http://example.com' });
  assert.deepEqual(opened.map(([method]) => method), ['page', 'tab', 'page', 'error']);
  assert.equal(opened[0][1], '/pages/store/index?id=M03&from=home');
  assert.equal(opened[1][1], '/pages/orders/index');

  let definition;
  global.Page = value => { definition = value; };
  require('../pages/webview/index');
  const page = { ...definition, data: { ...definition.data }, setData(data) { Object.assign(this.data, data); } };
  page.onLoad({ url: opened[2][1].split('?url=')[1] });
  assert.equal(page.data.url, external);
  page.webError();
  assert.equal(page.data.url, '');
  assert.ok(page.data.error);
});

test('首页轮播加载采用接口返回顺序，图片失效时移除该项', async () => {
  global.wx = {
    getStorageSync: () => 'https://api.example.com/api/user/v1',
    request({ url, header, success }) {
      assert.equal(url, 'https://api.example.com/api/user/v1/home-banners');
      assert.equal(header.Authorization, undefined);
      success({ statusCode: 200, data: { rows: [
        { id: 2, imagePath: '/home-banners/2/image?v=latest' },
        { id: 1, imagePath: '/home-banners/1/image?v=old' }
      ] } });
    }
  };
  let definition;
  global.Page = value => { definition = value; };
  require('../pages/home/index');
  const page = { ...definition, data: { ...definition.data }, setData(data) { Object.assign(this.data, data); } };
  await page.loadBanners();
  assert.deepEqual(page.data.banners.map(item => item.id), [2, 1]);
  page.bannerChanged({ detail: { current: 1 } });
  const { id, image } = page.data.banners[1];
  page.bannerImageError({ currentTarget: { dataset: { id: String(id), image } } });
  assert.deepEqual(page.data.banners.map(item => item.id), [2]);
  assert.equal(page.data.currentBanner, 0);
});
