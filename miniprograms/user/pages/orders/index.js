const api = require('../../utils/api');
Page({
  data: { rows: [], visible: [], filter: '全部', filters: ['全部', '待支付', '进行中', '已完成', '已关闭', '售后'], loading: true },
  onShow() { this.load(); },
  async load() {
    try {
      await getApp().ensureLogin();
      const { data } = await api.get('/orders');
      const rows = (data.rows || []).map(row => ({ ...row, amountText: `¥${(row.paidCents / 100).toFixed(2)}`, displayItems: row.items || '查看餐品详情' }));
      const pending = new Map();
      rows.filter(row => row.mergedPaymentStatus === '未支付').forEach(row => {
        if (!pending.has(row.paymentId)) pending.set(row.paymentId, { id: row.paymentId, paymentId: row.paymentId, isPayment: true, merchantCount: 0, names: [], items: [], amountCents: 0, createdAt: row.createdAt });
        const group = pending.get(row.paymentId);
        group.merchantCount += 1; group.names.push(row.merchantName); group.items.push(row.displayItems); group.amountCents += row.paidCents;
      });
      const unpaidRows = [...pending.values()].map(row => ({ ...row, merchantName: row.merchantCount > 1 ? `合并支付单 · ${row.merchantCount} 家商户` : row.names[0], displayItems: row.items.join('、'), amountText: `¥${(row.amountCents / 100).toFixed(2)}`, displayStatus: '待支付' }));
      this.setData({ rows, unpaidRows, loading: false }, () => this.applyFilter());
    } catch (error) { this.setData({ loading: false }); wx.showToast({ title: error.message || '请登录后查看订单', icon: 'none' }); }
  },
  chooseFilter(event) { this.setData({ filter: event.currentTarget.dataset.value }, () => this.applyFilter()); },
  applyFilter() {
    const { rows, unpaidRows, filter } = this.data;
    const merchantRows = rows.filter(row => row.mergedPaymentStatus !== '未支付');
    const visible = (filter === '全部' ? [...unpaidRows, ...merchantRows] : filter === '待支付' ? unpaidRows : merchantRows).filter(row => {
      if (row.isPayment) return filter === '全部' || filter === '待支付';
      const status = row.fulfillStatus;
      if (filter === '全部' || filter === '待支付') return false;
      if (filter === '进行中') return !['已完成', '已关闭'].includes(status) && row.paymentStatus === '支付成功';
      if (filter === '售后') return row.refundStatus && row.refundStatus !== '无售后';
      return status === filter;
    }).map(row => row.isPayment ? row : ({ ...row, displayStatus: row.fulfillStatus }));
    this.setData({ visible });
  },
  openOrder(event) { wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` }); },
  openPayment(event) { wx.navigateTo({ url: `/pages/payment/index?id=${event.currentTarget.dataset.id}` }); },
  goHome() { wx.switchTab({ url: '/pages/home/index' }); }
});
