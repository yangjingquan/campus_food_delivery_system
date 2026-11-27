function showDialog(page, options, onConfirm) {
  page.dialogConfirm = onConfirm;
  page.setData({ dialog: options });
}

const dialogEvents = {
  blockDialogScroll() {},
  scrimTapDialog() {
    if (this.data.dialog?.dismissible === false) return;
    this.cancelDialog();
  },
  cancelDialog() {
    this.dialogConfirm = null;
    this.setData({ dialog: null });
  },
  confirmDialog() {
    const confirm = this.dialogConfirm;
    const selected = this.data.dialog?.selected;
    this.cancelDialog();
    if (confirm) return confirm(selected);
  },
  selectDialogOption(event) {
    const index = Number(event.currentTarget.dataset.index);
    this.setData({ 'dialog.selected': index });
    if (this.data.dialog?.sheet && this.data.dialog?.autoConfirm) {
      return this.confirmDialog();
    }
  }
};

module.exports = { showDialog, dialogEvents };
