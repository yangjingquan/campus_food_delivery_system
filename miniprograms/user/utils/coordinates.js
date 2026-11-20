// 微信地图选点返回 GCJ-02 坐标；空字符串不能当作零坐标使用。
function hasCoordinates(address) {
  return !!address && [address.latitude, address.longitude].every(value =>
    (typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '' && Number.isFinite(Number(value)))
    && Math.abs(Number(address.latitude)) <= 90 && Math.abs(Number(address.longitude)) <= 180;
}

module.exports = { hasCoordinates };
