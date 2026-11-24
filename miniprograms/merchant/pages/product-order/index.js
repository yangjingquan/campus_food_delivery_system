const api = require('../../utils/api');

const MOVE_THRESHOLD = 6;
const EDGE_ZONE = 48;
const MAX_SCROLL_STEP = 18;
const SCROLL_INTERVAL = 24;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

Page({
  data: {
    rows: [], loading: true, changed: false, saving: false, conflict: false,
    dragFrom: -1, dragTo: -1, dragOffset: 0, scrollTop: 0
  },

  onLoad() { this.refresh(); },
  onHide() { this.cancelDrag(); },
  onResize() { this.cancelDrag(); },
  onUnload() {
    this.unloaded = true;
    this.cancelDrag();
  },

  async refresh() {
    this.cancelDrag();
    this.scrollPosition = 0;
    this.setData({ loading: true, error: '', saveError: '', conflict: false, changed: false, scrollTop: 0 });
    try {
      const products = await api.request('/products');
      if (this.unloaded) return;
      const rows = products.map(({ id, name, category, available, availableStock }) => ({
        id, name, category, available, availableStock
      }));
      this.initialIds = rows.map(item => item.id);
      this.setData({ rows });
    } catch (error) {
      if (!this.unloaded) this.setData({ error: error.message });
    } finally {
      if (!this.unloaded) this.setData({ loading: false });
    }
  },

  onScroll(event) {
    if (!this.drag?.active) this.scrollPosition = event.detail.scrollTop;
  },

  startDrag(event) {
    const { rows, saving, conflict } = this.data;
    if (this.drag || saving || conflict || rows.length < 2 || event.touches.length !== 1) return;
    const touch = event.touches[0];
    const from = Number(event.currentTarget.dataset.index);
    if (!rows[from]) return;
    this.suppressTapUntil = 0;
    const drag = { from, to: from, touchId: touch.identifier, startY: touch.clientY, y: touch.clientY };
    this.drag = drag;

    const query = wx.createSelectorQuery();
    query.select('.sort-scroll').fields({ rect: true, size: true, scrollOffset: true });
    query.select('.sort-row').boundingClientRect();
    query.exec(([viewport, row]) => {
      if (this.drag !== drag || !viewport?.height || !row?.height) return;
      drag.viewport = viewport;
      drag.rowHeight = row.height;
      this.updateDrag(drag);
    });
  },

  moveDrag(event) {
    const drag = this.drag;
    if (!drag) return;
    const touch = event.touches.find(item => item.identifier === drag.touchId);
    if (!touch) return;
    drag.y = touch.clientY;
    this.updateDrag(drag);
  },

  updateDrag(drag, state = {}) {
    if (!drag.viewport) return;
    if (!drag.active) {
      if (Math.abs(drag.y - drag.startY) < MOVE_THRESHOLD) return;
      drag.active = true;
      drag.scroll = drag.viewport.scrollTop ?? this.scrollPosition;
      drag.startScroll = drag.scroll;
      state = { dragFrom: drag.from, scrollTop: drag.scroll };
      this.scrollTimer = setInterval(() => this.scrollAtEdge(drag), SCROLL_INTERVAL);
    }

    // 拖动期间保留节点顺序，松手后再提交新的列表，避免触摸目标被移除。
    const lastIndex = this.data.rows.length - 1;
    const offset = clamp(drag.y - drag.startY + drag.scroll - drag.startScroll,
      -drag.from * drag.rowHeight, (lastIndex - drag.from) * drag.rowHeight);
    drag.to = drag.from + Math.round(offset / drag.rowHeight);
    this.setData({ ...state, dragTo: drag.to, dragOffset: offset });
  },

  scrollAtEdge(drag) {
    const { top, bottom, height } = drag.viewport;
    const edge = Math.min(EDGE_ZONE, height / 3);
    const up = Math.max(0, top + edge - drag.y);
    const down = Math.max(0, drag.y - bottom + edge);
    const step = Math.round(clamp((down - up) / edge, -1, 1) * MAX_SCROLL_STEP);
    const limit = Math.max(0, this.data.rows.length * drag.rowHeight - height);
    const scroll = clamp(drag.scroll + step, 0, limit);
    if (!step || scroll === drag.scroll) return;
    drag.scroll = scroll;
    this.scrollPosition = scroll;
    this.updateDrag(drag, { scrollTop: scroll });
  },

  endDrag(event) {
    const drag = this.drag;
    if (!drag || !event.changedTouches.some(item => item.identifier === drag.touchId)) return;
    this.finishDrag(true);
  },

  cancelDrag() { this.finishDrag(false); },

  finishDrag(commit) {
    const drag = this.drag;
    if (!drag) return;
    clearInterval(this.scrollTimer);
    this.drag = null;
    if (!drag.active || this.unloaded) return;
    this.suppressTapUntil = Date.now() + 350;
    const reset = { dragFrom: -1, dragTo: -1, dragOffset: 0 };
    if (commit && drag.from !== drag.to) this.applyOrder(this.movedRows(drag.from, drag.to), reset);
    else this.setData(reset);
  },

  moveOptions(event) {
    const { rows, saving, conflict } = this.data;
    if (saving || conflict || Date.now() < this.suppressTapUntil) return;
    const from = Number(event.currentTarget.dataset.index);
    if (!rows[from]) return;
    const options = [];
    if (from > 0) options.push({ label: '上移一位', index: from - 1 });
    if (from + 1 < rows.length) options.push({ label: '下移一位', index: from + 1 });
    if (!options.length) return;
    wx.showActionSheet({
      itemList: options.map(option => option.label),
      success: ({ tapIndex }) => {
        if (!this.unloaded) this.applyOrder(this.movedRows(from, options[tapIndex].index));
      }
    });
  },

  movedRows(from, to) {
    const rows = this.data.rows.slice();
    const [item] = rows.splice(from, 1);
    rows.splice(to, 0, item);
    return rows;
  },

  applyOrder(rows, state = {}) {
    const changed = rows.some((item, index) => item.id !== this.initialIds[index]);
    this.setData({ ...state, rows, changed, saveError: '' });
  },

  cancel() {
    if (!this.data.saving) wx.navigateBack();
  },

  async save() {
    const { rows, changed, saving, conflict } = this.data;
    if (!changed || saving || conflict || this.drag?.active) return;
    this.setData({ saving: true, saveError: '' });
    try {
      await api.request('/products/display-order', {
        previousProductIds: this.initialIds,
        productIds: rows.map(item => item.id)
      }, 'PUT');
      if (this.unloaded) return;
      wx.showToast({ title: '顺序已保存' });
      wx.navigateBack();
    } catch (error) {
      if (!this.unloaded) this.setData({ saveError: error.message, conflict: error.status === 409 });
    } finally {
      if (!this.unloaded) this.setData({ saving: false });
    }
  }
});
