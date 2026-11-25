const roles = require('../../utils/roles');

function register(page) {
  const { onLoad, onShow, onHide, onUnload, onPullDownRefresh } = page;
  Page({
    ...page,
    data: { ...page.data, roleReady: false, roleError: '' },
    onLoad(options) {
      this.entryOptions = options;
    },
    onShow() {
      wx.hideHomeButton?.();
      this.rolePageVisible = true;
      return this.showMerchantPage();
    },
    onHide() {
      this.rolePageVisible = false;
      if (this.rolePageLoaded) onHide?.call(this);
    },
    onUnload() {
      this.rolePageVisible = false;
      if (this.rolePageLoaded) onUnload?.call(this);
    },
    onPullDownRefresh() {
      if (this.rolePageLoaded && onPullDownRefresh) return onPullDownRefresh.call(this);
      return this.showMerchantPage().finally(() => wx.stopPullDownRefresh());
    },
    openRolePicker() { roles.openPicker('merchant'); },
    async showMerchantPage() {
      if (this.checkingRole) return;
      if (roles.activeRole() !== 'merchant') {
        this.checkingRole = true;
        this.setData({ roleReady: false, roleError: '' });
        try {
          const audit = await roles.status('merchant');
          if (!this.rolePageVisible) return;
          if (audit !== 'APPROVED') {
            await roles.showVerification('merchant', audit);
            return;
          }
          roles.activate('merchant');
        } catch (error) {
          if (this.rolePageVisible) this.setData({ roleError: error.message });
          return;
        } finally {
          this.checkingRole = false;
        }
      }
      this.setData({ roleReady: true, roleError: '' });
      if (!this.rolePageLoaded) {
        this.rolePageLoaded = true;
        onLoad?.call(this, this.entryOptions);
      }
      return onShow?.call(this);
    }
  });
}

module.exports = { register };
