Page({
  data: { orderId: '' },

  onLoad(query) { this.setData({ orderId: query.id || '' }); },
  refreshLocation() { return this.selectComponent('#order-location')?.loadLocation(); }
});
