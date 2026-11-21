const tabPages = ['/pages/home/index', '/pages/orders/index', '/pages/mine/index'];

function isExternalLink(url) {
  return typeof url === 'string'
    && /^https:\/\/[a-z0-9.-]+(?::\d+)?(?:[/?#]|$)/i.test(url)
    && !/[\s\\]/.test(url);
}

function showLinkError() {
  wx.showToast({ title: '暂时无法打开此链接', icon: 'none' });
}

function openBannerLink({ linkType, linkUrl }) {
  if (linkType === 'external' && isExternalLink(linkUrl)) {
    return wx.navigateTo({ url: '/pages/webview/index?url=' + encodeURIComponent(linkUrl), fail: showLinkError });
  }
  if (linkType === 'page' && /^\/pages\/[a-z0-9-]+\/index(?:\?[^#]*)?$/.test(linkUrl)) {
    const [path] = linkUrl.split('?');
    if (tabPages.includes(path)) {
      return wx.switchTab({ url: path, fail: showLinkError });
    }
    return wx.navigateTo({ url: linkUrl, fail: showLinkError });
  }
  showLinkError();
}

module.exports = { isExternalLink, openBannerLink };
