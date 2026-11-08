const api = require('../../utils/api');
const ui = require('../../utils/ui');

Page({
  data: { loading: true, loggedIn: false, form: { name: '', contact: '', phone: '', address: '' } },
  onLoad(options) { this.edit = options.edit === '1'; this.refresh(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  async refresh() {
    this.setData({ loading: true, error: '', loggedIn: !!api.token() });
    try {
      if (!api.token()) return;
      const { store, revision } = await api.request('/profile');
      const form = (revision && revision.status === '已驳回' ? revision : store) || this.data.form;
      const editable = !store || store.auditStatus === '已驳回' ||
        (store.auditStatus === '已通过' && this.edit && (!revision || revision.status !== '待审核'));
      this.setData({ store, revision, form, editable, photo: '', photoError: '' });
      if (store && store.hasPhoto) {
        try { this.setData({ photo: await api.photo() }); }
        catch (error) { this.setData({ photoError: error.message }); }
      }
    } catch (error) {
      this.setData({ error: error.message, loggedIn: !!api.token() });
    } finally {
      this.setData({ loading: false });
    }
  },
  login() {
    ui.action(this, async () => {
      await api.login();
      await this.refresh();
    });
  },
  input(event) { this.setData({ ['form.' + event.currentTarget.dataset.field]: event.detail.value }); },
  choosePhoto() {
    this.setData({ photoError: '' });
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        const image = tempFiles && tempFiles[0];
        if (!image || !image.tempFilePath) {
          this.setData({ photoError: '未能读取所选图片，请重新选择' });
          return;
        }
        this.readPhoto(image.tempFilePath, image.size);
      },
      fail: ({ errno, errMsg = '' } = {}) => {
        if (/cancel/i.test(errMsg)) return;
        if (errno === 112 || /not declared in the privacy agreement/i.test(errMsg)) {
          this.setData({ photoError: '小程序尚未在微信公众平台声明选图用途，请联系管理员配置用户隐私保护指引' });
          return;
        }
        if (/privacy|permission|auth deny/i.test(errMsg)) {
          this.setData({ photoError: '微信图片权限不可用，请检查隐私授权或相册权限后重试' });
          return;
        }
        this.setData({ photoError: '图片选择失败，请重试' });
      }
    });
  },
  readPhoto(path, size) {
    if (size > 1048576) {
      this.setData({ photoError: '请选择不超过 1 MB 的店铺图片' });
      return;
    }
    wx.getFileSystemManager().readFile({
      filePath: path,
      encoding: 'base64',
      success: ({ data }) => {
        const type = data.startsWith('iVBORw0KGgo') ? 'image/png' : data.startsWith('/9j/') ? 'image/jpeg' : '';
        if (!type) {
          this.setData({ photoError: '请选择 JPEG 或 PNG 图片' });
          return;
        }
        this.image = 'data:' + type + ';base64,' + data;
        this.setData({ photo: path, photoError: '', formError: '' });
      },
      fail: () => this.setData({ photoError: '图片读取失败，请重新选择' })
    });
  },
  save() {
    ui.action(this, async () => {
      const { name, contact, phone, address } = this.data.form;
      if (![name, contact, phone, address].every(value => value.trim())) throw new Error('请填写店铺名称、联系人、电话和地址');
      if (!this.data.store && !this.image) throw new Error('请上传店铺图片');
      await api.request('/profile', { name, contact, phone, address, image: this.image || '' }, 'POST');
      this.image = '';
      await this.refresh();
    });
  },
  workbench() { wx.switchTab({ url: '/pages/orders/index' }); }
});
