Component({
  properties: {
    inline: Boolean,
    selected: String
  },
  data: {
    tabs: [
      { page: 'home', icon: 'home', label: '配送台' },
      { page: 'orders', icon: 'orders', label: '我的配送' },
      { page: 'wallet', icon: 'wallet', label: '收益' },
      { page: 'mine', icon: 'user', label: '我的' }
    ]
  },
  methods: {
    select(event) {
      const page = event.currentTarget.dataset.page;
      if (page !== this.data.selected) wx.switchTab({ url: '/pages/' + page + '/index' });
    }
  }
});
