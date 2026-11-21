const { isExternalLink } = require('../../utils/banner-link');

Page({
  data: { url: '', error: '' },

  onLoad(query) {
    let url = query.url || '';
    if (!isExternalLink(url)) {
      try { url = decodeURIComponent(url); } catch (_) { url = ''; }
    }
    if (isExternalLink(url)) this.setData({ url });
    else this.setData({ error: '链接地址无效。' });
  },

  webError() {
    this.setData({ url: '', error: '暂时无法打开此网页。' });
  }
});
