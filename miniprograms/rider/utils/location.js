const api = require('./api');

const permissionKey = 'rider-location-explained';
let enabled = false;
let busy = false;
let generation = 0;
let timer;
let state = { message: '配送期间经授权后按订单共享位置。', available: false };
const listeners = new Set();

function publish(next) {
  state = next;
  listeners.forEach(listener => listener(state));
}

async function authorize() {
  const setting = await api.wxCall('getSetting');
  if (!wx.getStorageSync(permissionKey) || !setting.authSetting['scope.userLocation']) {
    const answer = await api.wxCall('showModal', {
      title: '配送位置共享',
      content: '领餐后，在小程序前台仅向当前配送订单的用户和商户共享位置。订单完成或关闭后即停止共享。',
      confirmText: '允许定位'
    });
    if (!answer.confirm) return false;
    wx.setStorageSync(permissionKey, true);
    try {
      if (setting.authSetting['scope.userLocation'] === false) {
        const result = await api.wxCall('openSetting');
        return !!result.authSetting['scope.userLocation'];
      }
      await api.wxCall('authorize', { scope: 'scope.userLocation' });
    } catch {
      return false;
    }
  }
  return true;
}

async function sample() {
  if (!enabled || busy || !wx.getStorageSync(permissionKey) || !wx.getStorageSync('campus-rider-token')) return;
  const run = generation;
  busy = true;
  try {
    const profile = await api.request('/profile');
    const orders = profile.auditStatus === '已通过' ? await api.request('/orders?scope=active') : [];
    if (run !== generation) return;
    if (!orders.length) {
      publish({ message: '暂无配送中订单，已停止为订单采集位置。', available: false });
      return;
    }
    const setting = await api.wxCall('getSetting');
    if (run !== generation) return;
    if (!setting.authSetting['scope.userLocation']) {
      publish({ message: '定位权限未开启，位置暂不可用。', available: false, capturedAt: state.capturedAt });
      return;
    }
    const location = await api.wxCall('getLocation', { type: 'gcj02', isHighAccuracy: true });
    if (run !== generation) return;
    const capturedAt = Date.now();
    const { latitude, longitude, accuracy: accuracyM } = location;
    const results = await Promise.allSettled(orders.map(order =>
      api.request('/orders/' + encodeURIComponent(order.id) + '/location', 'POST',
        { latitude, longitude, accuracyM, capturedAt })
    ));
    if (run !== generation) return;
    const synced = results.filter(result => result.status === 'fulfilled').length;
    publish({
      message: synced === orders.length ? '配送期间按订单共享位置，订单完成或关闭后结束该单共享。'
        : '部分订单未更新位置，请刷新配送列表或检查网络。',
      available: synced > 0,
      syncedIds: orders.filter((order, index) => results[index].status === 'fulfilled').map(order => order.id),
      latitude,
      longitude,
      capturedAt
    });
  } catch {
    if (run === generation) {
      publish({ message: '定位或网络暂不可用，请检查权限和网络。', available: false, capturedAt: state.capturedAt });
    }
  } finally {
    busy = false;
  }
}

function start() {
  if (enabled) return;
  enabled = true;
  sample();
  timer = setInterval(sample, 20000);
}

function stop() {
  generation++;
  enabled = false;
  clearInterval(timer);
  publish({ message: '前台定位已暂停。', available: false, capturedAt: state.capturedAt });
}

function listen(callback) {
  listeners.add(callback);
  callback(state);
  return () => listeners.delete(callback);
}

module.exports = { authorize, sample, start, stop, listen };
