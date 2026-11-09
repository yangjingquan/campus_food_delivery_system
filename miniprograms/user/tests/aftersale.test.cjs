const test = require('node:test');
const assert = require('node:assert/strict');
const { availability } = require('../utils/aftersale');

const hour = 60 * 60 * 1000;
const order = {
  paidCents: 1800, refundedCents: 500, foodCents: 1600, foodRefundedCents: 500,
  accepted: true, fulfillStatus: '已关闭', mergedPaymentStatus: '支付成功', firstClosedAt: hour
};

test('部分退款关闭后，首关时限内仍可申请剩余退款', () => {
  const result = availability(order, 24 * hour);
  assert.equal(result.allowed, true);
  assert.equal(result.canPartial, true);
  assert.equal(result.food, 1100);
});

test('后续退款记录不重置 24 小时售后时限', () => {
  const result = availability({ ...order, closedAt: 23 * hour }, 25 * hour + 1);
  assert.equal(result.allowed, false);
});

test('有在途售后时，保留进度入口并禁止重复申请', () => {
  assert.equal(availability({ ...order, pendingRefund: true }, hour * 2).allowed, false);
});
