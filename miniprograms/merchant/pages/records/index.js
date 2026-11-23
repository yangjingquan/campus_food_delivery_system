const api = require('../../utils/api');
const ui = require('../../utils/ui');
const labels = { settlements: '订单结算', debts: '欠款与扣回', ledger: '资金流水', withdrawals: '提现记录' };
const events = {
  debt_recover: '欠款扣回', debt_receive: '平台收回垫付款', withdraw_freeze: '提现冻结',
  rider_refund_debit: '全额退款骑手费用扣款', withdraw_unfreeze: '提现解冻', withdraw_paid: '提现出款', settlement_credit: '订单结算入账'
};

function receiptUnavailable() {
  const { platform } = typeof wx.getDeviceInfo === 'function' ? wx.getDeviceInfo() : wx.getSystemInfoSync();
  if (['devtools', 'windows', 'mac'].includes(platform)) {
    return '请使用原收款微信账号，在手机微信中打开商户小程序确认收款。';
  }
  if (!wx.canIUse('requestMerchantTransfer')) {
    return '请更新手机微信后，再打开商户小程序确认收款。';
  }
  return '';
}

function showReceiptError(error, parameters) {
  const message = String(error?.errMsg || error?.err_msg || '');
  let detail = message.replace(/^requestMerchantTransfer:\s*(?:fail\b[ :：]*)?/i, '');
  if (/^(?:user )?cancel(?:led|ed)?[.!]?$/i.test(detail.trim())) return;

  const code = [error?.errCode, error?.errno].find(value => /^-?\d{1,10}$/.test(String(value)));
  for (const value of [parameters.appId, parameters.mchId, parameters.package]) {
    detail = detail.split(value).join('[已隐藏]');
  }
  detail = detail.replace(/https?:\/\/\S+|[a-z0-9+/_=-]{16,}/gi, '[已隐藏]').trim().slice(0, 100);
  wx.showModal({
    title: '微信收款页打开失败',
    content: [detail, code === undefined ? '' : '错误码 ' + code].filter(Boolean).join('。')
      || '请检查微信版本，并用原收款微信账号重新打开商户小程序。',
    showCancel: false
  });
}

Page({
  data: { rows: [], loading: true, confirmingId: '' },
  onLoad(options) {
    this.kind = labels[options.kind] ? options.kind : 'withdrawals';
    this.setData({ kind: this.kind, title: labels[this.kind] });
    wx.setNavigationBarTitle({ title: labels[this.kind] });
  },
  onShow() { this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '' });
    try {
      const rows = (await api.request('/finance/' + this.kind)).map(item => ({
        ...item,
        time: this.kind === 'withdrawals' ? item.createdAt : ui.date(item.createdAt || item.settlementBaseAt),
        label: events[item.eventType] || item.eventType,
        amount: ui.money(item.amountCents), food: ui.money(item.foodCents),
        refunded: ui.money(item.foodRefundedCents),
        foodBase: ui.money(item.foodBaseCents),
        foodRule: item.foodRuleValue == null ? '' : item.foodRuleMode === 'FIXED'
          ? ui.money(item.foodRuleValue) : item.foodRuleValue / 100 + '%',
        foodCommission: item.foodCommissionCents == null ? '' : ui.money(item.foodCommissionCents),
        net: item.settledCents == null ? '' : ui.money(item.settledCents),
        netTime: ui.date(item.settledAt),
        riderAmount: ui.money(item.riderDueCents),
        advance: ui.money(item.advanceCents), recovered: ui.money(item.recoveredCents),
        debt: ui.money(item.advanceCents - item.recoveredCents),
        available: ui.money(item.availableDelta), frozen: ui.money(item.frozenDelta)
      }));
      this.setData({ rows });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  async confirmWithdrawal(event) {
    const { id } = event.currentTarget.dataset;
    if (this.data.confirmingId) return;
    const unavailable = receiptUnavailable();
    if (unavailable) {
      wx.showModal({ title: '请在手机微信确认收款', content: unavailable, showCancel: false });
      return;
    }
    this.setData({ confirmingId: id });
    try {
      const receipt = await api.request('/finance/withdrawals/' + encodeURIComponent(id) + '/confirmation');
      const { appId, mchId, package: confirmationPackage } = receipt || {};
      if (typeof appId !== 'string' || !appId || typeof mchId !== 'string' || !/^\d{1,32}$/.test(mchId)
          || typeof confirmationPackage !== 'string' || !confirmationPackage.trim() || confirmationPackage.length > 1024) {
        throw new Error('收款参数无效，请刷新提现记录后重试');
      }
      if (appId !== wx.getAccountInfoSync().miniProgram.appId) {
        throw new Error('当前小程序与原提现单不匹配，请打开原商户小程序');
      }
      const parameters = { appId, mchId, package: confirmationPackage };
      await new Promise(resolve => wx.requestMerchantTransfer({
        ...parameters,
        success: resolve,
        fail(error) {
          showReceiptError(error, parameters);
          resolve();
        }
      }));
    } catch (error) {
      wx.showToast({ title: error.message, icon: 'none' });
    } finally {
      await this.refresh();
      this.setData({ confirmingId: '' });
    }
  },
  order(event) { ui.go('/pages/order/index?id=' + encodeURIComponent(event.currentTarget.dataset.id)); }
});
