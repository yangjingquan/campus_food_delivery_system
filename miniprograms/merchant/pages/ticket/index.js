const api = require('../../utils/api');
const ui = require('../../utils/ui');
const { apiEnvironment } = require('../../utils/config');

const printLabels = {
  unavailable: '打印服务待更新',
  none: '尚未打印', waiting: '等待提交', sending: '正在提交',
  submitted: '已提交打印', failed: '提交失败', unknown: '结果待确认'
};

Page({
  data: { loading: true, canPrint: false },
  onLoad(options) {
    this.id = options.id;
    this.storageKey = 'merchant-print:' + apiEnvironment + ':' + this.id;
    this.requestKey = wx.getStorageSync(this.storageKey) || '';
  },
  onShow() { this.visible = true; this.refresh(); },
  onHide() { this.visible = false; clearTimeout(this.timer); },
  onUnload() { this.onHide(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    clearTimeout(this.timer);
    this.setData({ loading: !this.data.ticket, error: '' });
    try {
      const response = await api.request(ui.orderPath(this.id) + '/ticket', {}, 'POST');
      const canPrint = !!response.printing && Object.prototype.hasOwnProperty.call(printLabels, response.printing.status)
        && response.printing.status !== 'unavailable';
      const printing = canPrint ? response.printing : {
        status: 'unavailable', message: '当前接口未提供云打印状态，请更新商户接口服务后刷新页面。'
      };
      // 旧版小票接口从店铺资料和订单详情补齐预览，缺少打印字段时仍可查看、复制小票。
      const [profile, order] = await Promise.all([
        response.merchantName ? null : api.request('/profile').catch(() => null),
        response.address ? null : api.request(ui.orderPath(this.id)).catch(() => null)
      ]);
      const delivery = order && order.delivery;
      const ticket = {
        ...response, printing,
        pickup: response.fulfillmentType === 'pickup',
        pickupCode: response.pickupCode || (response.pickupNumber ? '#' + response.pickupNumber : ''),
        merchantName: response.merchantName || (profile && profile.store && profile.store.name) || '商户小票',
        address: response.address || (delivery && [delivery.building, delivery.room].filter(Boolean).join(' ')) || '当前接口未提供',
        createdTime: response.createdTime || (order && order.createdAt && ui.date(order.createdAt)) || '当前接口未提供'
      };
      this.setData({
        ticket, canPrint, store: { name: ticket.merchantName },
        paid: ui.money(ticket.paidCents), fee: ui.money(ticket.deliveryCents),
        printLabel: printLabels[printing.status] || '请刷新状态',
        printPending: ['waiting', 'sending'].includes(printing.status),
        lines: ticket.lines.map(item => ({
          ...item, unitPrice: ui.money(item.priceCents), amount: ui.money(item.amountCents)
        }))
      });
      if (this.visible && this.data.printPending) {
        this.timer = setTimeout(() => this.refresh(), 2500);
      }
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  print() {
    if (!this.data.canPrint || this.data.printPending) return;
    return ui.action(this, async () => {
      if (!this.requestKey) {
        this.requestKey = 'print_' + Date.now() + '_' + Math.random().toString(36).slice(2);
        wx.setStorageSync(this.storageKey, this.requestKey);
      }
      const result = await api.request(ui.orderPath(this.id) + '/print', { requestKey: this.requestKey }, 'POST');
      this.requestKey = '';
      wx.removeStorageSync(this.storageKey);
      this.setData({ formError: '' });
      await this.refresh();
      wx.showToast({ title: printLabels[result.status] || '补打申请已提交', icon: 'none' });
    });
  },
  copy() {
    const { ticket, store, fee, paid, lines } = this.data;
    const pickupCode = ticket.pickupCode ? [ticket.pickupCode] : [];
    const items = lines.map(item => item.name + '  ' + item.unitPrice + ' × ' + item.quantity + '  ' + item.amount);
    wx.setClipboardData({
      data: [store.name, ...pickupCode, ...(ticket.pickup ? ['到店自取'] : []),
        '订单号 ' + ticket.id, '名称  单价  数量  金额', ...items, '----------------',
        ...(ticket.pickup ? [] : ['配送费 ' + fee]), '总金额 ' + paid,
        (ticket.pickup ? '取餐地点 ' : '送货地址 ') + ticket.address, '下单时间 ' + ticket.createdTime,
        ...(ticket.code ? ['领取码 ' + ticket.code] : [])].join('\n')
    });
  }
});
