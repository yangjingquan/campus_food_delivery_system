const cart = require('./cart');

let openingCheckout = false;

function openCart() {
  const pages = getCurrentPages();
  const previous = pages[pages.length - 2];
  if (previous?.route === 'pages/cart/index') return wx.navigateBack();
  return wx.navigateTo({ url: '/pages/cart/index' });
}

function openCheckout() {
  if (openingCheckout || !cart.read().length) return;
  openingCheckout = true;
  return wx.navigateTo({
    url: '/pages/confirm/index',
    complete: () => { openingCheckout = false; }
  });
}

module.exports = { openCart, openCheckout };
