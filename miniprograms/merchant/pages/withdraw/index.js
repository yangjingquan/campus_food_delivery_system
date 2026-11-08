const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true, amount: '' },
  onLoad() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const { account, debtCents, merchantId } = await api.request('/finance');
      const max = Math.max(0, account.availableCents - debtCents);
      this.storageKey = 'merchant-withdraw-' + merchantId;
      const intent = wx.getStorageSync(this.storageKey);
      if (intent) this.setData({ amount: (intent.amountCents / 100).toFixed(2), pending: true });
      this.setData({ max, available: ui.money(max), debt: ui.money(debtCents) });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  input(event) { this.setData({ amount: event.detail.value }); },
  submit() {
    ui.action(this, async () => {
      const amountCents = ui.cents(this.data.amount);
      // 请求结果不明时保留原幂等键，页面重开也能重查同一笔申请。
      const storageKey = this.storageKey;
      let intent = wx.getStorageSync(storageKey);
      if (intent && intent.amountCents !== amountCents) {
        throw new Error('有一笔结果待确认，请以原金额 ' + ui.money(intent.amountCents) + ' 重查申请');
      }
      if (!intent) {
        if (amountCents > this.data.max) throw new Error('提现金额超过可提现余额');
        if (!await ui.confirm('确认申请提现', '申请 ' + ui.money(amountCents) + '，提交后进入管理员审核。')) return;
        intent = { amountCents, requestKey: 'wd_' + Date.now() + '_' + Math.random().toString(36).slice(2) };
        wx.setStorageSync(storageKey, intent);
        this.setData({ pending: true });
      }
      let result;
      try {
        result = await api.request('/finance/withdrawals', intent, 'POST');
      } catch (error) {
        if (error.status >= 400 && error.status < 500) {
          wx.removeStorageSync(storageKey);
          this.setData({ pending: false });
        }
        throw error;
      }
      wx.removeStorageSync(storageKey);
      this.setData({ pending: false });
      if (result.status !== '已受理') {
        await this.refresh();
        throw new Error('扣回欠款后的可提现余额不足，请核对账户');
      }
      wx.redirectTo({ url: '/pages/records/index?kind=withdrawals' });
    });
  }
});
