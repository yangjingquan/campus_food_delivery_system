// 两种取餐方式各用一份购物篮，首单自取不会带上已有外卖餐品。
function storageKey(mode) { return mode === 'pickup' ? 'pickupCart' : 'shoppingCart'; }
function read(merchantScope, mode) {
  const items = wx.getStorageSync(storageKey(mode)) || [];
  if (Array.isArray(merchantScope)) return items.filter(item => merchantScope.includes(item.merchantId));
  return merchantScope ? items.filter(item => item.merchantId === merchantScope) : items;
}
function save(items, mode) { wx.setStorageSync(storageKey(mode), items); return items; }
function totals(items, feeForMerchant) {
  let foodCents = 0;
  let deliveryFeeCents = 0;
  const grouped = groups(items);
  for (const group of grouped) {
    const subtotal = group.items.reduce((sum, item) => sum + item.priceCents * item.quantity, 0);
    foodCents += subtotal;
    const rule = typeof feeForMerchant === 'function' ? feeForMerchant(group.merchantId) : feeForMerchant;
    deliveryFeeCents += rule?.mode === 'PERCENTAGE'
      ? Math.floor((subtotal * rule.value + 5000) / 10000)
      : (rule?.value ?? rule ?? 0);
  }
  return { foodCents, deliveryFeeCents, totalCents: foodCents + deliveryFeeCents, merchantCount: grouped.length };
}
function feeRule(merchant, config) {
  return merchant?.deliveryFeeRule ?? merchant?.deliveryFeeCents ?? config?.deliveryFeeRule ?? config?.deliveryFeeCents ?? 0;
}
function add(items, product, merchant, delta = 1, mode) {
  const cart = [...items];
  const current = cart.find(item => item.productId === product.id);
  if (current) {
    current.quantity = delta < 0
      ? Math.max(0, current.quantity + delta)
      : Math.max(current.quantity, Math.min(99, product.availableStock, current.quantity + delta));
  }
  else if (delta > 0 && product.availableStock > 0) cart.push({
    productId: product.id, merchantId: merchant.id, merchantName: merchant.name,
    name: product.name, description: product.description, priceCents: product.priceCents,
    quantity: Math.min(99, delta, product.availableStock), availableStock: product.availableStock,
    lineKey: `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`,
    artwork: product.artwork, color: product.color, photoPath: product.photoPath
  });
  return save(cart.filter(item => item.quantity > 0), mode);
}
function groups(items) {
  const result = new Map();
  items.forEach(item => {
    if (!result.has(item.merchantId)) result.set(item.merchantId, { merchantId: item.merchantId, merchantName: item.merchantName, items: [] });
    result.get(item.merchantId).items.push(item);
  });
  return [...result.values()];
}
module.exports = { read, save, totals, feeRule, add, groups };
