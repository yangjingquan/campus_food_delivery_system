function money(cents) {
  return cents == null ? '—' : (Number(cents) / 100).toFixed(2);
}

function campusDate(value) {
  return new Date(Number(value) + 8 * 3600000);
}

function date(value) {
  if (!value) return '—';
  if (typeof value === 'string' && !/^\d+$/.test(value)) return value;
  const clock = campusDate(value);
  const part = number => String(number).padStart(2, '0');
  return part(clock.getUTCMonth() + 1) + '-' + part(clock.getUTCDate()) + ' ' +
    part(clock.getUTCHours()) + ':' + part(clock.getUTCMinutes());
}

function order(data) {
  return {
    ...data,
    address: data.building + ' ' + data.room,
    fee: money(data.feeCents),
    commission: money(data.commissionCents),
    due: money(data.dueCents),
    paidTime: date(data.paidAt),
    arrivedTime: date(data.arrivedAt),
    pickupTime: date(data.pickedUpAt),
    endTime: date(data.closedAt || data.completedAt),
    startTime: date(data.lastRefundAt || data.completedAt),
    eligibleTime: date(data.eligibleAt),
    settledTime: date(data.settledAt),
    displayPhone: maskPhone(data.phone),
    hasCoordinates: data.latitude != null && data.longitude != null,
    refunds: (data.refunds || []).map(item => ({
      ...item,
      time: date(item.actualAt || item.createdAt),
      amount: money(item.amountCents)
    }))
  };
}

function maskPhone(phone) {
  return phone ? phone.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2') : '—';
}

function finance(data) {
  const keys = ['fee', 'commission', 'due', 'pending', 'held', 'available', 'frozen', 'paidOut'];
  return { ...data, ...Object.fromEntries(keys.map(key => [key, money(data[key + 'Cents'])])) };
}

function withdrawal(data) {
  return {
    ...data,
    amount: money(data.amountCents),
    time: date(data.createdAt),
    timeline: (data.timeline || []).map(item => ({ ...item, time: date(item.createdAt) }))
  };
}

function schedule(data) {
  const clock = campusDate(data.serverTime);
  const minutes = clock.getUTCHours() * 60 + clock.getUTCMinutes();
  const toMinutes = value => {
    const [hour, minute] = value.split(':').map(Number);
    return hour * 60 + minute;
  };
  const toClock = value => String(Math.floor(value / 60)).padStart(2, '0') + ':' + String(value % 60).padStart(2, '0');
  const config = data.config;
  const serviceStart = toMinutes(config.serviceStart);
  const serviceEnd = toMinutes(config.serviceEnd);
  const peakStart = toMinutes(config.peakStart);
  const peakEnd = toMinutes(config.peakEnd);
  const windows = [
    [serviceStart, peakStart, config.regularBatch],
    [peakStart, peakEnd, config.peakBatch],
    [peakEnd, serviceEnd, config.regularBatch]
  ];
  const departures = windows.flatMap(([start, end, interval]) => {
    const result = [];
    if (interval < 1 || start >= end) return result;
    for (let time = start + interval; time < end; time += interval) result.push(time);
    result.push(end);
    return result;
  });
  const planned = [...departures.filter(value => value > minutes).map(toClock),
    ...departures.map(value => '明日 ' + toClock(value))].slice(0, 3);
  const operating = minutes >= serviceStart && minutes < serviceEnd;
  const peak = minutes >= peakStart && minutes < peakEnd;
  return {
    ...data,
    dayLabel: (clock.getUTCMonth() + 1) + '月' + clock.getUTCDate() + '日 · 周' + '日一二三四五六'[clock.getUTCDay()],
    current: planned[0] || '—',
    next: planned[1] || '—',
    later: planned[2] || '—',
    period: !operating ? '运营时段外' : peak ? '高峰时段' : '常规时段',
    interval: peak ? config.peakBatch : config.regularBatch,
    operating,
    waiting: data.waiting.map(item => ({
      ...item,
      arrived: date(item.arrivedAt),
      overdue: item.alertAt && data.serverTime >= item.alertAt
    }))
  };
}

module.exports = { money, date, order, maskPhone, finance, withdrawal, schedule };
