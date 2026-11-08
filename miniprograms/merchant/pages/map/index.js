const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true, markers: [] },
  onLoad(options) { this.id = options.id; },
  onShow() {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 10000);
  },
  onHide() { this.stop(); },
  onUnload() { this.stop(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  stop() { clearInterval(this.timer); },
  async refresh() {
    if (this.inFlight) return;
    this.inFlight = true;
    try {
      const position = await api.request(ui.orderPath(this.id) + '/location');
      const { latitude, longitude } = position;
      const markers = position.available ? [{
        id: 1, latitude, longitude, iconPath: '/assets/rider.png', width: 32, height: 32
      }] : [];
      this.setData({ position, markers, time: ui.date(position.capturedAt), error: '' });
      if (position.state === 'ended') this.stop();
    } catch (error) {
      this.setData({ position: null, markers: [], error: error.message });
    } finally {
      this.setData({ loading: false });
      this.inFlight = false;
    }
  }
});
