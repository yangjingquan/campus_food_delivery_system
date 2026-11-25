const { register } = require('../../utils/page');
const api = require('../../../utils/merchant/api');
const ui = require('../../utils/ui');
const filterStatuses = ['', 'PRODUCT_AVAILABLE', 'PRODUCT_UNLISTED', 'PRODUCT_SOLD_OUT'];
const MOVE_THRESHOLD = 6;
const ORDER_CONFLICT = '商品列表或顺序已变化，请重新加载后再调整';
const dragReset = { dragLocked: false, dragFrom: -1, dragTo: -1, dragOffset: 0 };

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function filteredRows(rows, filter) {
  return filter ? rows.filter(item => item.status === filterStatuses[filter]) : rows;
}

function sameOrder(first, second) {
  return first.length === second.length && first.every((row, index) => row.id === second[index].id);
}

register({
  data: {
    rows: [], visible: [], thumbnails: {}, filter: 0, labels: ['全部商品', '在售', '已下架', '已售罄'],
    loading: true, refreshing: false, orderChanged: false, orderSaving: false, orderConflict: false,
    scrollTop: 0, ...dragReset
  },
  onShow() {
    this.pageVisible = true;
    this.refresh();
  },
  onHide() {
    this.pageVisible = false;
    this.cancelDrag();
    this.stopThumbnails();
  },
  onUnload() {
    this.unloaded = true;
    this.onHide();
  },
  onResize() { this.cancelDrag(); },
  onboarding: ui.onboarding,

  refresh() { return this.loadProducts(); },
  reloadOrder() { return this.loadProducts(true); },
  async pullRefresh() {
    if (this.drag || this.data.orderSaving) {
      this.setData({ refreshing: false });
      return;
    }
    this.setData({ refreshing: true });
    try {
      await this.refresh();
    } finally {
      if (!this.unloaded) this.setData({ refreshing: false });
    }
  },

  async loadProducts(discardOrder = false) {
    if (this.loadingProducts || this.data.orderSaving) return;
    this.loadingProducts = true;
    this.cancelDrag();
    this.stopThumbnails();
    this.setData({ loading: !this.savedRows, error: '', blocked: '' });
    try {
      const access = await ui.access();
      if (this.unloaded) return;
      this.setData(access);
      if (access.blocked || this.storeId !== access.store.id) {
        this.savedRows = null;
        this.setData({
          rows: [], visible: [], thumbnails: {}, orderChanged: false, orderConflict: false, orderError: ''
        });
      }
      if (access.blocked) return;
      this.storeId = access.store.id;
      const fresh = (await api.request('/products')).map(item => ({
        ...item, price: ui.money(item.priceCents),
        status: !item.available ? 'PRODUCT_UNLISTED' : item.availableStock <= 0 ? 'PRODUCT_SOLD_OUT' : 'PRODUCT_AVAILABLE'
      }));
      if (this.unloaded) return;
      const { rows, orderChanged } = this.data;
      const keepDraft = !discardOrder && orderChanged && !sameOrder(rows, fresh);
      if (keepDraft && !sameOrder(this.savedRows, fresh)) {
        this.setData({ orderConflict: true, orderError: ORDER_CONFLICT });
        return;
      }
      this.savedRows = fresh;
      const freshById = new Map(fresh.map(row => [row.id, row]));
      this.setRows(keepDraft ? rows.map(row => freshById.get(row.id)) : fresh, {
        thumbnails: {}, orderChanged: keepDraft, orderConflict: false, orderError: ''
      });
    } catch (error) {
      if (!this.unloaded) this.setData(this.data.orderChanged ? { orderError: error.message } : { error: error.message });
    } finally {
      this.loadingProducts = false;
      if (!this.unloaded) this.setData({ loading: false }, () => this.observeThumbnails());
    }
  },

  select(event) {
    if (this.drag?.active || this.data.orderSaving) return;
    this.cancelDrag();
    const filter = Number(event.currentTarget.dataset.index);
    this.setData({ filter, visible: filteredRows(this.data.rows, filter) }, () => this.observeThumbnails());
  },

  setRows(rows, state, callback) {
    this.setData({ ...state, rows, visible: filteredRows(rows, this.data.filter) }, callback);
  },

  stopThumbnails() {
    if (this.thumbnailObserver) this.thumbnailObserver.disconnect();
    this.thumbnailObserver = null;
  },

  observeThumbnails() {
    this.stopThumbnails();
    const { visible, loading, blocked, error } = this.data;
    if (!this.pageVisible || loading || blocked || error || !visible.some(item => item.hasPhoto)) return;

    const requested = new Set();
    const observer = this.createIntersectionObserver({ observeAll: true });
    this.thumbnailObserver = observer;
    observer.relativeTo('.products-scroll').observe('.load-thumbnail', async entry => {
      const id = entry.dataset.id;
      if (this.thumbnailObserver !== observer || !entry.intersectionRatio) return;
      if (requested.has(id) || this.data.thumbnails[id]) return;
      requested.add(id);
      try {
        const path = await api.photo('/products/' + encodeURIComponent(id) + '/photo');
        if (this.thumbnailObserver === observer) this.setData({ thumbnails: { ...this.data.thumbnails, [id]: path } });
      } catch {
        // 图片加载失败时保留占位图，商品仍可正常编辑。
      }
    });
  },

  edit(event) {
    if (this.drag?.active || this.data.orderSaving) return;
    const id = event.currentTarget.dataset.id;
    ui.go('/merchant/pages/product/index' + (id ? '?id=' + encodeURIComponent(id) : ''));
  },

  toggle(event) {
    if (this.drag?.active || this.data.orderSaving || this.loadingProducts) return;
    const item = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    if (!item) return;
    ui.action(this, async () => {
      if (!await ui.confirm(item.available ? '确认下架' : '确认上架', item.name)) return;
      await api.request('/products/' + encodeURIComponent(item.id) + '/availability', { available: !item.available }, 'PUT');
      await this.refresh();
    });
  },

  onScroll(event) {
    if (!this.drag) this.scrollPosition = event.detail.scrollTop;
  },

  canReorder() {
    const { visible, loading, refreshing, saving, orderSaving, orderConflict } = this.data;
    return visible.length > 1 && !loading && !refreshing && !this.loadingProducts && !saving && !orderSaving && !orderConflict;
  },

  startDrag(event) {
    if (this.drag || !this.canReorder() || event.touches.length !== 1) return;
    const touch = event.touches[0];
    const from = Number(event.currentTarget.dataset.index);
    if (!this.data.visible[from]) return;
    this.suppressTapUntil = 0;
    const drag = { from, to: from, touchId: touch.identifier, startY: touch.clientY, y: touch.clientY };
    this.drag = drag;
    // 从按下手柄开始停用列表滚动和下拉刷新，避免同一触摸被两种手势处理。
    this.setData({ dragLocked: true, scrollTop: this.scrollPosition ?? 0 });

    const query = wx.createSelectorQuery();
    query.selectAll('.product-sort-row').boundingClientRect();
    query.exec(([slots]) => {
      if (this.drag !== drag || slots?.length !== this.data.visible.length) return;
      drag.slots = slots;
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

  updateDrag(drag) {
    if (!drag.slots) return;
    const { from, slots } = drag;
    const source = slots[from];
    const delta = drag.y - drag.startY;
    if (!drag.active && Math.abs(delta) < MOVE_THRESHOLD) return;
    drag.active = true;

    // 卡片高度随商品名称和库存信息变化，按相邻卡片的实际中点计算目标位置。
    const last = slots.length - 1;
    let to = from;
    while (delta > 0 && to < last && source.bottom + delta >= slots[to + 1].top + slots[to + 1].height / 2) to++;
    while (delta < 0 && to > 0 && source.top + delta <= slots[to - 1].top + slots[to - 1].height / 2) to--;
    drag.to = to;
    const dragOffset = clamp(delta, slots[0].top - source.top, slots[last].bottom - source.bottom);
    this.setData({ dragFrom: from, dragTo: to, dragOffset });
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
    this.drag = null;
    if (this.unloaded) return;
    if (drag.active) this.suppressTapUntil = Date.now() + 350;
    // 松手后再更新列表顺序，让触摸目标在拖动期间保持挂载。
    if (commit && drag.from !== drag.to) this.moveProduct(drag.from, drag.to, dragReset);
    else this.setData(dragReset);
  },

  moveOptions(event) {
    if (!this.canReorder() || Date.now() < this.suppressTapUntil) return;
    const from = Number(event.currentTarget.dataset.index);
    const { visible, filter } = this.data;
    const product = visible[from];
    if (!product) return;
    const options = [];
    if (from > 0) options.push({ label: '上移一位', step: -1 });
    if (from + 1 < visible.length) options.push({ label: '下移一位', step: 1 });
    wx.showActionSheet({
      itemList: options.map(option => option.label),
      success: ({ tapIndex }) => {
        const selected = options[tapIndex];
        if (!selected || this.unloaded || !this.canReorder()) return;
        if (filter === this.data.filter && sameOrder(visible, this.data.visible)) {
          this.moveProduct(from, from + selected.step);
        }
      }
    });
  },

  moveProduct(from, to, state = {}) {
    const visible = this.data.visible.slice();
    const [item] = visible.splice(from, 1);
    visible.splice(to, 0, item);

    // 筛选时只交换当前可见商品占据的位置，其他商品仍留在原位置。
    const visibleIds = new Set(visible.map(row => row.id));
    let index = 0;
    const rows = this.data.rows.map(row => visibleIds.has(row.id) ? visible[index++] : row);
    this.setRows(rows, { ...state, orderChanged: !sameOrder(this.savedRows, rows), orderError: '' },
      () => this.observeThumbnails());
  },

  cancelOrder() {
    if (this.data.orderSaving || this.data.orderConflict || this.loadingProducts || !this.savedRows) return;
    this.cancelDrag();
    this.setRows(this.savedRows, { orderChanged: false, orderError: '' }, () => this.observeThumbnails());
  },

  async saveOrder() {
    const { rows, orderChanged, orderSaving, orderConflict, saving } = this.data;
    if (!orderChanged || orderSaving || orderConflict || saving || this.loadingProducts || this.drag?.active) return;
    this.cancelDrag();
    this.setData({ orderSaving: true, orderError: '' });
    try {
      await api.request('/products/display-order', {
        previousProductIds: this.savedRows.map(item => item.id),
        productIds: rows.map(item => item.id)
      }, 'PUT');
      if (this.unloaded) return;
      this.savedRows = rows;
      this.setData({ orderChanged: false });
      if (this.pageVisible) wx.showToast({ title: '顺序已保存' });
    } catch (error) {
      if (!this.unloaded) this.setData({ orderError: error.message, orderConflict: error.status === 409 });
    } finally {
      if (!this.unloaded) this.setData({ orderSaving: false });
    }
  }
});
