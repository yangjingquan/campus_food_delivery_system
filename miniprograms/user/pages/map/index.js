const api = require('../../utils/api');
const { time } = require('../../utils/display');

Page({
  data: { markers: [], scale: 16, stateText: '正在查询位置', hint: '', loading: true, loadError: '' },

  onLoad(query) { this.orderId = query.id; },
  onShow() { this.visible = true; this.load(); },
  onHide() { this.visible = false; clearTimeout(this.poll); },
  onUnload() { this.onHide(); },

  async load() {
    if (this.refreshing) return;
    clearTimeout(this.poll);
    this.refreshing = true;
    this.setData({ loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}/location`);
      if (!this.visible) return;
      const latitude = Number(data.latitude);
      const longitude = Number(data.longitude);
      const hasCoordinates = data.latitude != null && data.longitude != null
        && Number.isFinite(latitude) && Number.isFinite(longitude);
      const showMarker = hasCoordinates && ['live', 'stale'].includes(data.state);
      const stale = data.state === 'stale';
      const markerTitle = stale ? '最后已知位置' : '骑手位置';
      this.setData({
        loading: false, stale,
        stateText: data.message || (showMarker ? markerTitle : '当前位置暂不可用'),
        hint: showMarker ? `更新于 ${time(data.capturedAt)}${stale ? ' · 非实时' : ''}` : '',
        latitude: showMarker ? latitude : 0,
        longitude: showMarker ? longitude : 0,
        markers: showMarker ? [{
          id: 1, latitude, longitude, iconPath: '/assets/rider-pin.png', width: 30, height: 34,
          callout: { content: markerTitle, display: 'ALWAYS', padding: 8, borderRadius: 8 }
        }] : []
      });
      if (data.state !== 'ended') {
        const delay = Math.min(15000, Math.max(3000, (data.expirySeconds || 60) * 500));
        this.poll = setTimeout(() => this.load(), delay);
      }
    } catch (error) {
      if (this.visible) this.setData({ loading: false, loadError: error.message, markers: [] });
    } finally {
      this.refreshing = false;
    }
  }
});
