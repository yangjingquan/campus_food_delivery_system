const location = require('./utils/location');

App({
  onShow() {
    location.start();
  },
  onHide() {
    location.stop();
  }
});
