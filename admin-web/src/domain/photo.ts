export function isPhotoFile(file: File) {
  return ['image/jpeg', 'image/png'].includes(file.type) && file.size > 0 && file.size <= 1024 * 1024
}

export function encodePhoto(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('图片读取失败，请重新选择'))
    reader.readAsDataURL(file)
  })
}
