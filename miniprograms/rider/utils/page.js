const api = require('./api');
const display = require('./display');
const location = require('./location');

const roots = ['home', 'orders', 'wallet', 'mine'];
const genderOptions = ['男', '女', '不便透露'];
const timeOptions = ['午间 11:00–13:00', '晚间 17:00–19:00', '午间与晚间', '其他时间'];
const auditLabels = {
  已通过: ['学生身份审核通过', '可以前往暂存点，逐单扫码领取餐品。', 'shield'],
  待审核: ['资料已提交，审核中', '审核通过后即可扫码领单。', 'clock'],
  已驳回: ['资料审核未通过', '请核对驳回原因，补齐资料后重新提交。', 'info'],
  未入驻: ['欢迎成为校园骑手', '完成入驻资料并通过学生身份审核后，即可开始配送。', 'user']
};
const help = {
  scan: ['如何领取多笔订单？', '请在暂存点逐份扫描餐品小票。每个领取码只对应一笔订单。'],
  settlement: ['收益何时可提现？', '正常完成且无未处理售后的订单满 24 小时后结算。发生退款时，从最后一次实际退款成功起重新计算 24 小时。'],
  permission: ['为什么需要位置信息？', '配送期间，小程序在前台按订单共享位置，仅该订单的用户和商户可查看。最后一笔配送结束后停止采集。'],
  refund: ['配送中收到退款怎么办？', '售后处理中可查看最新进度。退款成功关闭订单后请停止配送，原骑手报酬保留。'],
  contact: ['联系运营', '请联系校园暂存点的现场运营人员。']
};
const profileOnly = ['join', 'audit', 'profile', 'help', 'scan'];
const listPaths = { records: '/orders?scope=history', income: '/orders?scope=income', withdrawals: '/withdrawals?' };
const filters = {
  records: [{ value: '', label: '全部' }, { value: '已完成', label: '已完成' }, { value: '已关闭', label: '已关闭' }],
  income: [{ value: '', label: '全部' }, { value: '待结算', label: '待结算' },
    { value: '售后暂缓', label: '售后暂缓' }, { value: '可提现', label: '已可提现' }]
};

function path(page, id) {
  return '/pages/' + page + '/index' + (id ? '?id=' + encodeURIComponent(id) : '');
}

function navigate(page, id, replace = false) {
  if (roots.includes(page)) {
    wx.switchTab({ url: path(page) });
  } else {
    wx[replace ? 'redirectTo' : 'navigateTo']({ url: path(page, id) });
  }
}

function errorMessage(error) {
  return error?.message || '操作未完成，请稍后重试';
}

function toast(message) {
  wx.showToast({ title: message, icon: 'none' });
}

function applyFilter(items, filter, key) {
  return filter ? items.filter(item => item[key] === filter) : items;
}

function register(screen) {
  Page({
    data: {
      screen,
      root: roots.includes(screen),
      busy: false,
      loading: true,
      error: '',
      amount: '',
      filter: '',
      filters: filters[screen] || [],
      items: [],
      visibleItems: [],
      genderOptions,
      timeOptions,
      genderIndex: -1,
      timeIndex: -1,
      form: {},
      location: {},
      profile: {},
      active: []
    },

    onLoad(options) {
      this.orderId = options.id || '';
      this.withdrawKey = '';
      if (screen === 'scanResult') {
        this.setData({ result: getApp().scanResult || { ok: false, message: '请重新扫描小票二维码。' } });
      }
    },

    onShow() {
      this.stopListening = location.listen(status => {
        const shared = status.syncedIds?.includes(this.orderId);
        this.setData({
          location: { ...status, shared, time: status.capturedAt ? display.date(status.capturedAt) : '' }
        });
      });
      this.refresh();
    },

    onHide() {
      this.stopListening?.();
      if (screen === 'map') this.stopMapPolling();
    },

    onUnload() {
      this.stopListening?.();
      if (screen === 'map') this.stopMapPolling();
    },

    onPullDownRefresh() {
      this.refresh().finally(() => wx.stopPullDownRefresh());
    },

    onReachBottom() {
      if (listPaths[screen] && this.data.hasMore) this.more();
    },

    async refresh() {
      const sequence = (this.refreshSequence || 0) + 1;
      this.refreshSequence = sequence;
      this.setData({ loading: true, error: '' });
      try {
        const profile = await api.request('/profile');
        const approved = profile.auditStatus === '已通过';
        const gate = !approved && !profileOnly.includes(screen) && !['mine', 'home'].includes(screen);
        const patch = {
          profile: { ...profile, displayPhone: display.maskPhone(profile.phone), initial: (profile.name || '骑').slice(0, 1) },
          approved,
          gate: gate || (!approved && screen === 'scan'),
          audit: auditLabels[profile.auditStatus] || auditLabels.未入驻
        };
        if (approved && !profileOnly.includes(screen)) Object.assign(patch, await this.loadScreen());
        if (screen === 'join' && !this.formLoaded) {
          const timeIndex = timeOptions.indexOf(profile.availableTime);
          patch.form = { name: profile.name || '', phone: profile.phone || '', availableTime: profile.availableTime || '' };
          patch.genderIndex = genderOptions.indexOf(profile.gender);
          patch.timeIndex = timeIndex < 0 && profile.availableTime ? 3 : timeIndex;
          this.formLoaded = true;
        }
        if (this.refreshSequence === sequence) {
          this.setData({ ...patch, loaded: true });
          if (screen === 'map' && !this.mapTimer) {
            this.mapTimer = setInterval(() => this.refreshMap().catch(() => {}), 20000);
          }
        }
      } catch (error) {
        if (this.refreshSequence === sequence) this.setData({ error: errorMessage(error) });
      } finally {
        if (this.refreshSequence === sequence) this.setData({ loading: false });
      }
    },

    async loadScreen() {
      switch (screen) {
        case 'home': {
          const [active, batches, finance] = await Promise.all([
            api.request('/orders?scope=active'), api.request('/batches'), api.request('/finance')
          ]);
          return { active: active.map(display.order), batches: display.schedule(batches), finance: display.finance(finance) };
        }
        case 'waiting':
          return { batches: display.schedule(await api.request('/batches')) };
        case 'orders':
        case 'scanResult':
          return { active: (await api.request('/orders?scope=active')).map(display.order) };
        case 'map':
          return this.mapData();
        case 'wallet':
        case 'mine':
        case 'withdraw':
          return { finance: display.finance(await api.request('/finance')) };
        case 'detail':
        case 'incomeDetail':
          return { order: display.order(await api.request('/orders/' + encodeURIComponent(this.orderId))) };
        case 'withdrawDetail':
          return { withdrawal: display.withdrawal(await api.request('/withdrawals/' + encodeURIComponent(this.orderId))) };
        default:
          return listPaths[screen] ? this.loadItems(0) : {};
      }
    },

    async loadItems(offset) {
      const raw = await api.request(listPaths[screen] + (screen === 'withdrawals' ? '' : '&') + 'offset=' + offset);
      const items = [...(offset ? this.data.items : []), ...raw.map(screen === 'withdrawals' ? display.withdrawal : display.order)];
      return { items, visibleItems: applyFilter(items, this.data.filter, screen === 'income' ? 'incomeStatus' : 'status'),
        hasMore: raw.length === 30 };
    },

    async more() {
      if (this.data.loading || this.data.busy || !this.data.hasMore) return;
      this.setData({ busy: true });
      try {
        this.setData(await this.loadItems(this.data.items.length));
      } catch (error) {
        toast(errorMessage(error));
      } finally {
        this.setData({ busy: false });
      }
    },

    filter(event) {
      const filter = event.currentTarget.dataset.value;
      const visibleItems = applyFilter(this.data.items, filter, screen === 'income' ? 'incomeStatus' : 'status');
      this.setData({ filter, visibleItems });
    },

    async mapData() {
      const active = (await api.request('/orders?scope=active')).map(display.order);
      const current = active.find(item => item.id === this.orderId) || active[0];
      if (!current) return { active, order: null, markers: [] };
      this.orderId = current.id;
      const order = display.order(await api.request('/orders/' + encodeURIComponent(current.id)));
      const markers = order.hasCoordinates ? [{
        id: 1, latitude: Number(order.latitude), longitude: Number(order.longitude),
        title: order.building, iconPath: '/assets/pin-green.png', width: 28, height: 28
      }] : [];
      return { active, order, markers, location: { ...this.data.location,
        shared: this.data.location.syncedIds?.includes(order.id) || false } };
    },

    async refreshMap() {
      const request = (this.mapRequest || 0) + 1;
      this.mapRequest = request;
      const patch = await this.mapData();
      if (request === this.mapRequest) this.setData(patch);
    },

    stopMapPolling() {
      clearInterval(this.mapTimer);
      this.mapTimer = null;
    },

    selectMap(event) {
      this.orderId = event.currentTarget.dataset.id;
      this.refreshMap().catch(error => toast(errorMessage(error)));
    },

    go(event) {
      const { page, id } = event.currentTarget.dataset;
      navigate(page, id, screen === 'scanResult' && page === 'scan');
    },

    openModal(event) {
      const { topic, id, address } = event.currentTarget.dataset;
      if (topic === 'complete') {
        this.setData({ modal: { topic, id, title: '确认已送达？', text: '请核对 ' + address + '，并确认餐品已送到寝室门口。', confirm: '确认送达' } });
      } else {
        const [title, text] = help[topic];
        this.setData({ modal: { title, text, confirm: '我知道了' } });
      }
    },

    closeModal() {
      if (!this.data.busy) this.setData({ modal: null });
    },

    noop() {
    },

    async confirmModal() {
      const modal = this.data.modal;
      if (this.data.busy || !modal) return;
      if (!modal.topic) return this.closeModal();
      this.setData({ busy: true });
      try {
        if (modal.topic === 'complete') {
          await api.request('/orders/' + encodeURIComponent(modal.id) + '/complete', 'POST');
          toast('已确认送达');
          this.setData({ modal: null });
          await this.refresh();
          location.sample();
        } else if (modal.topic === 'withdraw') {
          const result = await api.request('/withdrawals', 'POST', { amountCents: modal.cents, requestKey: this.withdrawKey });
          if (!result.withdrawalId) {
            this.withdrawKey = '';
            wx.removeStorageSync('rider-withdraw-intent-' + this.data.profile.id);
            this.setData({ fieldError: '可提现余额不足，请核对最新余额', modal: null });
            await this.refresh();
            return;
          }
          this.withdrawKey = '';
          wx.removeStorageSync('rider-withdraw-intent-' + this.data.profile.id);
          this.setData({ modal: null, amount: '' });
          navigate('withdrawDetail', result.withdrawalId, true);
        }
      } catch (error) {
        toast(errorMessage(error));
        if (modal.topic === 'complete') {
          this.setData({ modal: null });
          await this.refresh();
        }
      } finally {
        this.setData({ busy: false });
      }
    },

    async scan() {
      if (this.data.busy) return;
      this.setData({ busy: true });
      try {
        if (!await location.authorize()) {
          toast('请开启配送定位权限后扫码领取');
          return;
        }
        let code;
        try {
          code = await api.wxCall('scanCode', { onlyFromCamera: true, scanType: ['qrCode'] });
        } catch (error) {
          if (String(error.errMsg).includes('cancel')) return;
          throw new Error('无法打开扫码，请检查摄像头权限');
        }
        const result = await api.request('/pickup', 'POST', { token: code.result });
        const detail = await api.request('/orders/' + encodeURIComponent(result.orderId)).catch(() => null);
        getApp().scanResult = { ok: true, order: detail ? display.order(detail) : null };
        location.sample();
      } catch (error) {
        getApp().scanResult = { ok: false, message: errorMessage(error) };
      } finally {
        this.setData({ busy: false });
      }
      navigate('scanResult', '', true);
    },

    async subscribe() {
      const id = this.data.batches?.reminderTemplateId;
      if (!id) return toast('运营方尚未配置配送提醒模板');
      try {
        const result = await api.wxCall('requestSubscribeMessage', { tmplIds: [id] });
        if (result[id] === 'accept') {
          await api.request('/batches/subscription', 'POST');
          toast('已订阅配送提醒');
        } else {
          toast('未授权配送提醒');
        }
      } catch (error) {
        toast(error.message || '订阅暂不可用，请稍后重试');
      }
    },

    async authorizeLocation() {
      try {
        if (await location.authorize()) location.sample();
      } catch {
        toast('请在微信设置中检查位置权限');
      }
    },

    openMap() {
      const order = this.data.order;
      if (!order?.hasCoordinates) return toast('该交付点缺少坐标，请按文字地址配送');
      wx.openLocation({
        latitude: Number(order.latitude),
        longitude: Number(order.longitude),
        name: order.building,
        address: order.address,
        scale: 17,
        fail: () => toast('暂时无法打开地图')
      });
    },

    call() {
      if (!this.data.order?.phone) return toast('当前订单无法联系收餐人');
      wx.makePhoneCall({ phoneNumber: this.data.order.phone });
    },

    amountInput(event) {
      this.setData({ amount: event.detail.value, fieldError: '' });
    },

    withdrawAll() {
      this.setData({ amount: this.data.finance.available, fieldError: '' });
    },

    reviewWithdrawal() {
      const amount = this.data.amount.trim();
      if (!/^\d{1,9}(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) {
        return this.setData({ fieldError: '请输入大于 0 且最多两位小数的金额' });
      }
      const [whole, decimals = ''] = amount.split('.');
      const cents = Number(whole) * 100 + Number(decimals.padEnd(2, '0'));
      // 网络结果不明时保留原请求键，退出页面后再次提交同一笔金额仍可取回原结果。
      const storageKey = 'rider-withdraw-intent-' + this.data.profile.id;
      const prior = wx.getStorageSync(storageKey) || {};
      if (prior.cents !== cents && cents > this.data.finance.availableCents) {
        return this.setData({ fieldError: '提现金额超过可提现余额' });
      }
      this.withdrawKey = prior.cents === cents ? prior.key : 'w' + Date.now() + Math.random().toString(36).slice(2);
      wx.setStorageSync(storageKey, { cents, key: this.withdrawKey });
      this.setData({ modal: {
        topic: 'withdraw', cents, title: '核对提现申请',
        text: '提现 ¥' + display.money(cents) + ' 至当前微信账户。提交后金额将冻结，等待管理员审核。',
        confirm: '确认提交'
      } });
    },

    formInput(event) {
      this.setData({ ['form.' + event.currentTarget.dataset.field]: event.detail.value });
    },

    openPicker(event) {
      if (this.data.busy) return;
      const gender = event.currentTarget.dataset.field === 'gender';
      const selected = gender ? this.data.genderIndex : this.data.timeIndex;
      this.setData({
        picker: {
          gender,
          title: gender ? '性别' : '可送餐时间',
          options: gender ? genderOptions : timeOptions,
          index: Math.max(0, selected)
        }
      });
    },

    selectPickerOption(event) {
      if (!this.data.picker) return;
      this.setData({ 'picker.index': Number(event.currentTarget.dataset.index) });
    },

    closePicker() {
      this.setData({ picker: null });
    },

    confirmPicker() {
      if (!this.data.picker) return;
      const { gender, index } = this.data.picker;
      if (gender) {
        this.setData({ genderIndex: index, picker: null });
      } else {
        const availableTime = index === 3
          ? (this.data.timeIndex === 3 ? this.data.form.availableTime : '')
          : timeOptions[index];
        this.setData({ timeIndex: index, 'form.availableTime': availableTime, picker: null });
      }
    },

    changeConsent(event) {
      this.setData({ consent: event.detail.value.includes('agree') });
    },

    async chooseCard() {
      try {
        const { tempFiles } = await api.wxCall('chooseMedia', {
          count: 1, mediaType: ['image'], sizeType: ['compressed'], sourceType: ['album', 'camera']
        });
        if (tempFiles[0].size > 4 * 1024 * 1024) return toast('请选择 4 MB 以内的学生证照片');
        this.setData({ studentCard: tempFiles[0].tempFilePath });
      } catch (error) {
        if (!String(error.errMsg).includes('cancel')) toast('暂时无法选择照片');
      }
    },

    previewCard() {
      if (this.data.studentCard) wx.previewImage({ urls: [this.data.studentCard] });
    },

    async submitApplication() {
      if (this.data.busy) return;
      const { form, genderIndex, consent, studentCard } = this.data;
      let fieldError = '';
      if (!form.name?.trim()) fieldError = '请输入姓名';
      else if (!/^1[3-9]\d{9}$/.test(form.phone || '')) fieldError = '请输入有效的手机号';
      else if (genderIndex < 0) fieldError = '请选择性别';
      else if (!form.availableTime?.trim()) fieldError = '请选择或填写可送餐时间';
      else if (!studentCard) fieldError = '请上传学生证照片';
      else if (!consent) fieldError = '请确认学生证及联系方式的使用说明';
      this.setData({ fieldError });
      if (fieldError) return;
      this.setData({ busy: true });
      try {
        await api.apply({ ...form, gender: genderOptions[genderIndex] }, studentCard);
        navigate('audit', '', true);
      } catch (error) {
        this.setData({ fieldError: errorMessage(error) });
      } finally {
        this.setData({ busy: false });
      }
    },

    async confirmReceipt() {
      const confirmation = this.data.withdrawal?.confirmation;
      if (!confirmation) return;
      if (!wx.canIUse('requestMerchantTransfer')) return toast('请更新微信后确认收款');
      try {
        await api.wxCall('requestMerchantTransfer', confirmation);
      } catch {
        toast('请在记录中查看最新收款状态');
      }
      await this.refresh();
    }
  });
}

module.exports = { register };
