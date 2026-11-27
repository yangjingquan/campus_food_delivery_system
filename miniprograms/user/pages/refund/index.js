const api = require('../../utils/api');
const { money } = require('../../utils/display');
const { availability } = require('../../utils/aftersale');
const { showDialog, dialogEvents } = require('../../utils/dialog');

Page({
  ...dialogEvents,
  data: {
    order: null, type: 'partial', amount: '', reasons: ['餐品有问题', '配送等待过久', '不想要了', '其他原因'],
    reasonIndex: 0, photoPreview: '', photoDataUrl: '', choosingPhoto: false,
    error: '', loading: true, loadError: '', submitting: false
  },

  onLoad(query) { this.orderId = query.id; this.load(); },

  async load() {
    this.setData({ loading: true, loadError: '' });
    try {
      await getApp().ensureLogin();
      const { data } = await api.get(`/orders/${this.orderId}`);
      const refund = availability(data);
      this.setData({
        order: data, refund, type: refund.canPartial ? 'partial' : 'full',
        amount: refund.canPartial ? '' : (refund.remaining / 100).toFixed(2),
        paidText: money(data.paidCents), foodText: money(data.foodCents), refundedText: money(data.refundedCents), feeText: money(data.deliveryFeeCents),
        returnDeliveryText: data.partialDeliveryRefundCents > 0 ? money(data.partialDeliveryRefundCents) : '',
        remainingFoodText: money(refund.food), remainingPaidText: money(refund.remaining), loading: false
      });
    } catch (error) {
      this.setData({ loading: false, loadError: error.message });
    }
  },

  chooseType(event) {
    const type = event.currentTarget.dataset.value;
    const amount = type === 'full' ? (this.data.refund.remaining / 100).toFixed(2) : '';
    this.setData({ type, amount, error: '' });
  },

  inputAmount(event) { this.setData({ amount: event.detail.value, error: '' }); },
  chooseReason() {
    showDialog(this, {
      title: '选择申请原因', options: this.data.reasons, selected: this.data.reasonIndex,
      sheet: true, autoConfirm: true, cancel: true
    }, reasonIndex => {
      if (reasonIndex != null) this.setData({ reasonIndex });
    });
  },
  inputDescription(event) { this.description = event.detail.value; },

  choosePhoto() {
    if (this.data.choosingPhoto || this.data.submitting) return;
    this.setData({ choosingPhoto: true, error: '' });
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        const image = tempFiles?.[0];
        if (!image?.tempFilePath) return;
        if (image.size > 1048576) {
          this.setData({ error: '凭证图片须不超过 1 MB，请重新选择。' });
          return;
        }
        wx.getFileSystemManager().readFile({
          filePath: image.tempFilePath,
          encoding: 'base64',
          success: ({ data }) => {
            const mime = data.startsWith('iVBORw0KGgo') ? 'image/png' : data.startsWith('/9j/') ? 'image/jpeg' : '';
            if (!mime) {
              this.setData({ error: '请上传 JPEG 或 PNG 格式图片。' });
              return;
            }
            this.setData({ photoPreview: image.tempFilePath, photoDataUrl: `data:${mime};base64,${data}` });
          },
          fail: () => this.setData({ error: '无法读取所选图片，请重试。' })
        });
      },
      fail: ({ errMsg = '' } = {}) => {
        if (!/cancel/i.test(errMsg)) this.setData({ error: '无法打开相册或相机，请检查权限后重试。' });
      },
      complete: () => this.setData({ choosingPhoto: false })
    });
  },
  removePhoto() {
    this.setData({ photoPreview: '', photoDataUrl: '' });
  },
  previewPhoto() {
    if (this.data.photoPreview) wx.previewImage({ current: this.data.photoPreview, urls: [this.data.photoPreview] });
  },

  async submit() {
    const { refund, type, reasons, reasonIndex, photoDataUrl } = this.data;
    if (this.data.submitting || !refund?.allowed) return;
    const input = this.data.amount.trim();
    if (!/^\d{1,8}(\.\d{1,2})?$/.test(input)) return this.setData({ error: '请输入金额，最多两位小数。' });
    const [yuan, fraction = ''] = input.split('.');
    const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, '0'));
    const limit = type === 'full' ? refund.remaining : Math.min(refund.food, refund.remaining);
    if (cents <= 0 || cents > limit) return this.setData({ error: `请输入大于零且不超过 ${money(limit)} 的金额。` });

    this.setData({ submitting: true, error: '' });
    try {
      const { data } = await api.post(`/orders/${this.orderId}/aftersales`, {
        type, amountCents: cents, reason: reasons[reasonIndex], description: this.description || '',
        ...(photoDataUrl ? { photo: photoDataUrl } : {})
      });
      wx.redirectTo({ url: `/pages/aftersale/index?id=${data.id}` });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ submitting: false });
    }
  }
});
