const api = require('../../utils/api');
Page({
  data: { location: null, markers: [], latitude: 31.2304, longitude: 121.4737, scale: 16, stale: false, stateText: '正在查询位置', hint: '位置仅用于本订单配送展示。' },
  async onLoad(query) { this.orderId = query.id; await this.load(); },
  async onShow() { if (this.orderId) await this.load(); },
  async load() {
    try {
      const { data } = await api.get(`/orders/${this.orderId}/location`);
      const hasCoordinates = Number.isFinite(Number(data.latitude)) && Number.isFinite(Number(data.longitude));
      const available = hasCoordinates && ['live', 'stale'].includes(data.state);
      const stale = data.state === 'stale';
      this.setData({ location: data, stale, stateText: data.message || (available ? '骑手正在配送' : '当前无可查看的骑手位置'), hint: available ? `位置更新时间 ${new Date(data.capturedAt).toLocaleTimeString()}${stale ? ' · 非实时' : ''}` : (data.message || '骑手领取后将开始位置共享'), latitude: available ? Number(data.latitude) : this.data.latitude, longitude: available ? Number(data.longitude) : this.data.longitude, markers: available ? [{ id: 1, latitude: Number(data.latitude), longitude: Number(data.longitude), width: 36, height: 44, callout: { content: data.riderName ? `${data.riderName} · ${stale ? '最后已知位置' : '配送中'}` : (stale ? '最后已知位置 · 非实时' : '骑手位置'), display: 'ALWAYS', padding: 8, borderRadius: 8 } }] : [] });
    } catch (error) { wx.showToast({ title: error.message || '位置暂不可用', icon: 'none' }); }
  },
  refresh() { this.load(); }
});
