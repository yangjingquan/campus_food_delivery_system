const api = require('../../utils/api');
const ui = require('../../utils/ui');
const { choosePhoto } = require('../../utils/photo');

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
  async choosePhoto() {
    this.setData({ photoError: '' });
    try {
      const photo = await choosePhoto();
      if (!photo) return;
      this.image = photo.dataUrl;
      this.setData({ photo: photo.path, formError: '' });
    } catch (error) {
      this.setData({ photoError: error.message });
    }
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
