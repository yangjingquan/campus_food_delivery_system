const api = require('../../utils/api');
const { hasCoordinates } = require('../../utils/coordinates');
const { time } = require('../../utils/display');

const initialLocation = {
  markers: [], latitude: 0, longitude: 0, stale: false,
  stateText: '正在获取骑手位置…', updatedAt: ''
};

Component({
  properties: { orderId: String },
  data: initialLocation,

  lifetimes: {
    attached() { this.start(); },
    detached() { this.stop(); }
  },
  pageLifetimes: {
    show() { this.start(); },
    hide() { this.stop(); }
  },

  methods: {
    start() {
      if (this.active) return;
      this.active = true;
      this.setData(initialLocation);
      return this.loadLocation();
    },

    stop() {
      this.active = false;
      clearTimeout(this.poll);
      this.request = null;
    },

    async loadLocation() {
      const { orderId } = this.data;
      if (!this.active || !orderId || this.request) return;
      clearTimeout(this.poll);
      const request = {};
      this.request = request;
      let delay = 15000;
      try {
        await getApp().ensureLogin();
        if (this.request !== request) return;
        const { data } = await api.get(`/orders/${encodeURIComponent(orderId)}/location`);
        if (this.request !== request) return;

        const stale = data.state === 'stale';
        const showMarker = (data.state === 'live' || stale) && hasCoordinates(data);
        const latitude = showMarker ? Number(data.latitude) : 0;
        const longitude = showMarker ? Number(data.longitude) : 0;
        const markerTitle = stale ? '最后已知位置' : '骑手位置';
        this.setData({
          latitude, longitude, stale: showMarker && stale,
          stateText: data.message || (showMarker ? markerTitle : '当前位置暂不可用'),
          updatedAt: showMarker ? time(data.capturedAt) : '',
          markers: showMarker ? [{
            id: 1, latitude, longitude, iconPath: '/assets/rider-pin.png', width: 30, height: 34,
            callout: { content: markerTitle, display: 'ALWAYS', padding: 8, borderRadius: 8 }
          }] : []
        });
        if (data.state === 'ended') {
          delay = 0;
          this.triggerEvent('ended');
        } else {
          delay = Math.min(15000, Math.max(3000, (data.expirySeconds || 60) * 500));
        }
      } catch (error) {
        if (this.request !== request) return;
        const missingOrder = error.statusCode === 404;
        this.setData({
          ...initialLocation,
          stateText: missingOrder ? '此订单的位置暂不可用。' : '暂时无法获取骑手位置，稍后自动重试。'
        });
        if (missingOrder) delay = 0;
      } finally {
        if (this.request === request) {
          this.request = null;
          if (delay > 0) this.poll = setTimeout(() => this.loadLocation(), delay);
        }
      }
    }
  }
});
