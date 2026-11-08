function choosePhoto() {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        const image = tempFiles && tempFiles[0];
        if (!image || !image.tempFilePath) {
          reject(new Error('未能读取所选图片，请重新选择'));
          return;
        }
        if (image.size > 1048576) {
          reject(new Error('请选择不超过 1 MB 的图片'));
          return;
        }

        // 预览使用临时路径，提交时才把 Base64 图片随表单发送。
        wx.getFileSystemManager().readFile({
          filePath: image.tempFilePath,
          encoding: 'base64',
          success: ({ data }) => {
            const type = data.startsWith('iVBORw0KGgo') ? 'image/png' : data.startsWith('/9j/') ? 'image/jpeg' : '';
            if (!type) {
              reject(new Error('请选择 JPEG 或 PNG 图片'));
              return;
            }
            resolve({ path: image.tempFilePath, dataUrl: 'data:' + type + ';base64,' + data });
          },
          fail: () => reject(new Error('图片读取失败，请重新选择'))
        });
      },
      fail: ({ errno, errMsg = '' } = {}) => {
        if (/cancel/i.test(errMsg)) {
          resolve(null);
        } else if (errno === 112 || /not declared in the privacy agreement/i.test(errMsg)) {
          reject(new Error('小程序尚未在微信公众平台声明选图用途，请联系管理员配置用户隐私保护指引'));
        } else if (/privacy|permission|auth deny/i.test(errMsg)) {
          reject(new Error('微信图片权限不可用，请检查隐私授权或相册权限后重试'));
        } else {
          reject(new Error('图片选择失败，请重试'));
        }
      }
    });
  });
}

module.exports = { choosePhoto };
