const api = require('./api');

function money(cents) {
  return `¥${(Number(cents || 0) / 100).toFixed(2)}`;
}

function deliveryFee(rule, cents) {
  return rule?.mode === 'PERCENTAGE'
    ? '餐品小计的 ' + (rule.value / 100) + '%'
    : money(rule?.value ?? cents);
}

function illustration(artwork) {
  const kind = ['rice', 'noodle', 'drink'].includes(artwork) ? artwork : 'rice';
  return `/assets/${kind}.svg`;
}

function photo(path, artwork) {
  return path ? api.baseUrl + path : illustration(artwork);
}

function time(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const local = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return local.toISOString().slice(0, 16).replace('T', ' ');
}

module.exports = { money, deliveryFee, illustration, photo, time };
