function read() { return wx.getStorageSync('shoppingCart') || []; }
function save(items) { wx.setStorageSync('shoppingCart', items); return items; }
function totals(items, feePerMerchant) {
  const foodCents = items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
  const merchantIds = [...new Set(items.map(item => item.merchantId))];
  const deliveryFeeCents = merchantIds.length * feePerMerchant;
  return { foodCents, deliveryFeeCents, totalCents: foodCents + deliveryFeeCents, merchantCount: merchantIds.length };
}
function add(items, product, merchant, delta = 1) {
  const cart = [...items];
  const current = cart.find(item => item.productId === product.id);
  if (current) current.quantity = Math.max(0, Math.min(product.availableStock, current.quantity + delta));
  else if (delta > 0 && product.availableStock > 0) cart.push({
    productId: product.id, merchantId: merchant.id, merchantName: merchant.name,
    name: product.name, description: product.description, priceCents: product.priceCents,
    quantity: Math.min(delta, product.availableStock), availableStock: product.availableStock,
    artwork: product.artwork, color: product.color
  });
  return save(cart.filter(item => item.quantity > 0));
}
function groups(items) {
  const result = new Map();
  items.forEach(item => {
    if (!result.has(item.merchantId)) result.set(item.merchantId, { merchantId: item.merchantId, merchantName: item.merchantName, items: [] });
    result.get(item.merchantId).items.push(item);
  });
  return [...result.values()];
}
module.exports = { read, save, totals, add, groups };
