const api = require('../../utils/api');
const { money, time } = require('../../utils/display');

Page({
  data: {
    rows: [], unpaidRows: [], visible: [], filter: '全部',
    filters: ['全部', '待支付', '进行中', '已完成', '已关闭', '售后'], loading: true, loadError: ''
  },

  onShow() {
    this.getTabBar?.()?.refresh('pages/orders/index');
    this.load();
  },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get('/orders');
      const rows = (data.rows || []).map(row => ({
        ...row, amountText: money(row.paidCents), displayItems: row.items,
        displayStatus: row.fulfillStatus,
        note: row.refundStatus && row.refundStatus !== '无售后' ? '售后：' + row.refundStatus : time(row.createdAt)
      }));

      // 未支付拆单按支付单归组。付款确认后，每家商户的订单分别显示。
      const pending = new Map();
      for (const row of rows) {
        if (row.mergedPaymentStatus !== '未支付') continue;
        if (!pending.has(row.paymentId)) {
          pending.set(row.paymentId, {
            id: row.paymentId, isPayment: true, names: [], items: [], amountCents: 0, note: time(row.createdAt)
          });
        }
        const group = pending.get(row.paymentId);
        group.names.push(row.merchantName);
        group.items.push(row.displayItems);
        group.amountCents += row.paidCents;
      }
      const unpaidRows = [...pending.values()].map(group => ({
        ...group, merchantName: group.names.length > 1 ? `合并支付 · ${group.names.length} 家商户` : group.names[0],
        displayItems: group.items.filter(Boolean).join('、'), displayStatus: '待支付',
        amountText: money(group.amountCents)
      }));
      this.setData({ rows, unpaidRows, loading: false }, () => this.applyFilter());
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  chooseFilter(event) {
    this.setData({ filter: event.currentTarget.dataset.value }, () => this.applyFilter());
  },

  applyFilter() {
    const { rows, unpaidRows, filter } = this.data;
    const orders = rows.filter(row => row.mergedPaymentStatus !== '未支付');
    const visible = orders.filter(row => {
      if (filter === '全部') return true;
      if (filter === '进行中') {
        return row.mergedPaymentStatus === '支付成功' && !['已完成', '已关闭'].includes(row.fulfillStatus);
      }
      if (filter === '售后') return row.refundStatus && row.refundStatus !== '无售后';
      return row.fulfillStatus === filter;
    });
    this.setData({
      visible: filter === '待支付' ? unpaidRows : filter === '全部' ? [...unpaidRows, ...visible] : visible
    });
  },

  openOrder(event) { wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` }); },
  openPayment(event) { wx.navigateTo({ url: `/pages/payment/index?id=${event.currentTarget.dataset.id}` }); },
  goHome() { wx.switchTab({ url: '/pages/home/index' }); }
});
