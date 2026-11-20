const cart = require('./cart');

let openingCheckout = false;

function openCart() {
  const pages = getCurrentPages();
  const previous = pages[pages.length - 2];
  if (previous?.route === 'pages/cart/index') return wx.navigateBack();
  return wx.navigateTo({ url: '/pages/cart/index' });
}

function openCheckout(merchantScope) {
  if (openingCheckout || !cart.read(merchantScope).length) return;
  let scope = '';
  if (Array.isArray(merchantScope)) {
    scope = '?merchantIds=' + merchantScope.map(id => encodeURIComponent(id)).join(',');
  } else if (merchantScope) {
    scope = '?merchantId=' + encodeURIComponent(merchantScope);
  }
  openingCheckout = true;
  return wx.navigateTo({
    url: '/pages/confirm/index' + scope,
    complete: () => { openingCheckout = false; }
  });
}

module.exports = { openCart, openCheckout };
