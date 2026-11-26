export interface PasswordChallenge {
  challengeId: string
  publicKey: string
}

const encode = new TextEncoder()
const curve = { name: 'ECDH', namedCurve: 'P-256' }

function toBase64(value: ArrayBuffer | Uint8Array) {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value)
  return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))
}

export function requirePasswordCrypto() {
  if (!globalThis.crypto?.subtle) {
    throw new Error('请通过 HTTPS 或本机 localhost 打开管理后台。')
  }
  return globalThis.crypto.subtle
}

export async function encryptPasswordRequest(challenge: PasswordChallenge, path: string, payload: object) {
  const subtle = requirePasswordCrypto()
  const serverKey = await subtle.importKey(
    'spki', Uint8Array.from(atob(challenge.publicKey), char => char.charCodeAt(0)), curve, false, [],
  )
  const keys = await subtle.generateKey(curve, false, ['deriveBits'])
  const shared = await subtle.deriveBits({ name: 'ECDH', public: serverKey }, keys.privateKey, 256)
  const secret = await subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey'])
  const key = await subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: encode.encode(challenge.challengeId),
      info: encode.encode(`campus-admin-password-v1:${path}`),
    },
    secret,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt'],
  )
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await subtle.encrypt({ name: 'AES-GCM', iv }, key, encode.encode(JSON.stringify(payload)))
  return {
    challengeId: challenge.challengeId,
    clientPublicKey: toBase64(await subtle.exportKey('spki', keys.publicKey)),
    iv: toBase64(iv),
    ciphertext: toBase64(ciphertext),
  }
}
