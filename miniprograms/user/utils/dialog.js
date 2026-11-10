function showDialog(page, options, onConfirm) {
  page.dialogConfirm = onConfirm;
  page.setData({ dialog: options });
}

const dialogEvents = {
  blockDialogScroll() {},
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
    this.setData({ 'dialog.selected': Number(event.currentTarget.dataset.index) });
  }
};

module.exports = { showDialog, dialogEvents };
