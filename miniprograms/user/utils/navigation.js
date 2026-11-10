function openCart() {
  const pages = getCurrentPages();
  const previous = pages[pages.length - 2];
  if (previous?.route === 'pages/cart/index') return wx.navigateBack();
  return wx.navigateTo({ url: '/pages/cart/index' });
}

module.exports = { openCart };
