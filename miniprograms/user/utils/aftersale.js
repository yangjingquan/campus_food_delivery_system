const DAY = 24 * 60 * 60 * 1000;

function availability(order, now = Date.now()) {
  const remaining = Math.max(0, order.paidCents - order.refundedCents);
  const food = Math.max(0, order.foodCents - order.foodRefundedCents);
  const anchor = order.completedAt || order.firstClosedAt;
  const beforeAcceptance = !order.accepted && order.fulfillStatus === 'AWAITING_MERCHANT';
  let reason = '';
  if (order.mergedPaymentStatus !== 'PAYMENT_SUCCEEDED') reason = '付款确认后可申请售后';
  else if (!remaining) reason = '这笔订单的款项已全部退回';
  else if (anchor && now > Number(anchor) + DAY) reason = '已超过 24 小时售后申请时限';
  else if (order.pendingRefund || ['PLATFORM_REVIEW', 'REFUND_VERIFYING'].includes(order.refundStatus)) reason = '已有售后正在处理';
  else if (order.refundStatus === 'REFUND_FAILED') reason = '平台正在核实原退款，请查看售后记录';
  else if (order.settlementStatus === 'WITHDRAWABLE') reason = '该订单已进入结算，请联系客服';
  return { allowed: !reason, reason, remaining, food, canPartial: !beforeAcceptance && food > 0 };
}

module.exports = { availability };
