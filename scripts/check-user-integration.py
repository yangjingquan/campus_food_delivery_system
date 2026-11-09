#!/usr/bin/env python3
"""Exercise the user mini-program API against an isolated MySQL schema via Compose."""
import json
import base64
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
suffix = secrets.token_hex(5)
database = 'campus_user_it_' + suffix
user = 'user_it_' + suffix
password = secrets.token_hex(20)
internal_key = secrets.token_hex(32)
admin_container = 'campus-user-admin-' + suffix
user_container = 'campus-user-api-' + suffix
created_db = created_user = created_admin = created_user_api = False

def run(args):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()

def root_sql(statement):
    return run(['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c', 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --default-character-set=utf8mb4 -uroot -N -e "$1"', '_', statement])

def call(method, path, body=None, token=None, key=None, content_type='application/json'):
    headers = {'Content-Type': content_type}
    if token: headers['Authorization'] = 'Bearer ' + token
    if key: headers['X-Internal-Key'] = key
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode('utf-8')
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=8) as response:
            payload = response.read()
            return response.status, payload if response.headers.get_content_type().startswith('image/') else json.loads(payload or b'{}')
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b'{}')

def expect(code, result, label):
    if result[0] != code: raise AssertionError(f'{label}: HTTP {result[0]} expected {code}: {result[1]}')
    return result[1]

def update_profile(token, display_name, avatar):
    boundary = 'campus-profile-test'
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="displayName"\r\n\r\n{display_name}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="avatar"; filename="avatar.png"\r\n'
        'Content-Type: image/png\r\n\r\n'
    ).encode() + avatar + f'\r\n--{boundary}--\r\n'.encode()
    return call('POST', '/api/user/v1/me', body, token, content_type=f'multipart/form-data; boundary={boundary}')

try:
    root_sql('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4')
    created_db = True
    root_sql("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'")
    created_user = True
    root_sql("GRANT ALL PRIVILEGES ON `" + database + "`.* TO '" + user + "'@'%'")
    run(['docker', 'compose', 'run', '--build', '-d', '--no-deps', '--name', admin_container, '-p', '127.0.0.1::8080',
         '-e', 'DB_URL=jdbc:mysql://mysql:3306/' + database + '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
         '-e', 'DB_USER=' + user, '-e', 'DB_PASSWORD=' + password, '-e', 'ADMIN_USER=user_it_admin',
         '-e', 'ADMIN_PASSWORD=' + secrets.token_urlsafe(20), '-e', 'INTERNAL_API_KEY=' + internal_key,
         '-e', 'NACOS_SERVER_ADDR=nacos:8848', '-e', 'SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false', 'admin-api'])
    created_admin = True
    run(['docker', 'compose', 'run', '--build', '-d', '--no-deps', '--name', user_container, '-p', '127.0.0.1::8081',
         '-e', 'DB_URL=jdbc:mysql://mysql:3306/' + database + '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
         '-e', 'DB_USER=' + user, '-e', 'DB_PASSWORD=' + password, '-e', 'SPRING_PROFILES_ACTIVE=local',
         '-e', 'WECHAT_USER_APP_ID=wxb2ef21a4d9800f4e', '-e', 'USER_PAYMENT_MODE=mock',
         '-e', 'USER_API_INTERNAL_KEY=' + internal_key, '-e', 'NACOS_SERVER_ADDR=nacos:8848',
         '-e', 'SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false', 'user-api'])
    created_user_api = True
    port = run(['docker', 'port', user_container, '8081/tcp']).split(':')[-1]
    base = 'http://127.0.0.1:' + port
    for _ in range(90):
        try:
            status, config = call('GET', '/api/user/v1/config')
            if status == 200: break
        except (OSError, urllib.error.URLError): pass
        time.sleep(1)
    else:
        for name in (admin_container, user_container):
            logs = subprocess.run(['docker', 'logs', name], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            print(f'--- {name} startup log ---\n{logs.stdout[-12000:]}', file=sys.stderr)
        raise RuntimeError('user-api did not start against the isolated schema')
    for _ in range(15):
        catalog = expect(200, call('GET', '/api/user/v1/catalog'), 'catalog')
        if any(shop['id'] == 'M03' and shop['products'] for shop in catalog['merchants']): break
        time.sleep(1)
    else: raise RuntimeError('local demo catalogue was not seeded')
    assert config['appId'] == 'wxb2ef21a4d9800f4e' and config['paymentMode'] == 'mock'
    token = expect(200, call('POST', '/api/user/v1/auth/wechat', {'code': 'local-user-it-01'}), 'mock login')['token']
    avatar = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aE1sAAAAASUVORK5CYII=')
    profile_path = '/api/user/v1/me'
    assert expect(200, update_profile(token, '资料测试', avatar), 'save nickname and avatar')['hasAvatar']
    expect(400, update_profile(token, '不应覆盖', b'not an image'), 'reject invalid avatar')
    assert expect(200, call('GET', profile_path, token=token), 'read saved profile')['displayName'] == '资料测试'
    assert expect(200, call('PUT', profile_path, {'displayName': '新昵称'}, token), 'update nickname only')['hasAvatar']
    assert expect(200, call('GET', profile_path + '/avatar', token=token), 'read own avatar') == avatar
    expect(401, call('GET', profile_path + '/avatar'), 'reject anonymous avatar access')
    other = expect(200, call('POST', '/api/user/v1/auth/wechat', {'code': 'local-user-it-02'}), 'other user login')
    expect(404, call('GET', profile_path + '/avatar', token=other['token']), 'other user cannot read avatar')
    relogin = expect(200, call('POST', '/api/user/v1/auth/wechat', {'code': 'local-user-it-01'}), 'login again')['user']
    assert relogin['displayName'] == '新昵称' and relogin['hasAvatar']
    address = expect(200, call('POST', '/api/user/v1/addresses', {'name': '测试同学', 'phone': '13800000000', 'building': '5号宿舍楼', 'room': '302寝室'}, token), 'create address')

    one = expect(200, call('POST', '/api/user/v1/checkout', {'addressId': address['id'], 'requestKey': 'single_test_001', 'items': [{'productId': 'P-M03-001', 'quantity': 1}]}, token), 'single-store checkout')
    assert (one['foodCents'], one['deliveryFeeCents'], one['totalCents'], one['merchantCount']) == (2000, 400, 2400, 1), one
    expect(200, call('POST', '/api/user/v1/payments/' + one['payment']['id'] + '/mock-success', {}, token), 'single-store mock payment')
    single_order = one['merchantOrders'][0]['id']
    refunded = expect(200, call('POST', '/api/user/v1/orders/' + single_order + '/aftersales', {'type': 'full', 'reason': '测试售后', 'description': '本地集成'}, token), 'pre-accept full refund')
    assert refunded['status'] == '退款成功', refunded

    multi = expect(200, call('POST', '/api/user/v1/checkout', {'addressId': address['id'], 'requestKey': 'multi_test_001', 'items': [{'productId': 'P-M03-002', 'quantity': 1}, {'productId': 'P-M04-001', 'quantity': 1}]}, token), 'multi-store checkout')
    assert (multi['foodCents'], multi['deliveryFeeCents'], multi['totalCents'], multi['merchantCount']) == (3400, 800, 4200, 2), multi
    expect(200, call('POST', '/api/user/v1/payments/' + multi['payment']['id'] + '/mock-success', {}, token), 'multi-store mock payment')
    order_id = multi['merchantOrders'][0]['id']
    aftersale_order = multi['merchantOrders'][1]['id']
    expect(200, call('POST', '/internal/v1/orders/' + aftersale_order + '/status', {'status': '商户出餐中'}, key=internal_key), 'merchant accepts independent order')
    expect(400, call('POST', '/api/user/v1/orders/' + aftersale_order + '/aftersales', {'type': 'partial', 'amountCents': 999999, 'reason': '金额边界测试'}, token), 'partial refund cap')
    partial = expect(200, call('POST', '/api/user/v1/orders/' + aftersale_order + '/aftersales', {'type': 'partial', 'amountCents': 500, 'reason': '餐品有问题', 'description': '本地部分退款联调'}, token), 'partial refund request')
    assert partial['status'] == '待商户处理', partial
    approved = expect(200, call('POST', '/internal/v1/aftersales/' + partial['id'] + '/decision', {'decision': 'approve'}, key=internal_key), 'approve partial refund')
    assert approved['status'] == '退款成功', approved
    closed = expect(200, call('GET', '/api/user/v1/orders/' + aftersale_order, token=token), 'partially refunded merchant order closed')
    untouched = expect(200, call('GET', '/api/user/v1/orders/' + order_id, token=token), 'other merchant order unaffected')
    assert closed['fulfillStatus'] == '已关闭' and closed['refundedCents'] == 500, closed
    assert untouched['fulfillStatus'] == '待商户接单' and untouched['refundedCents'] == 0, untouched
    near_deadline = int(time.time() * 1000) - 24 * 60 * 60 * 1000 + 4000
    root_sql('UPDATE `' + database + '`.user_order_detail SET first_closed_at=' + str(near_deadline) + " WHERE order_id='" + aftersale_order + "'")
    root_sql("UPDATE `" + database + "`.payment_order SET last_refund_at=0 WHERE id='" + multi['payment']['id'] + "'")
    again = expect(200, call('POST', '/api/user/v1/orders/' + aftersale_order + '/aftersales', {'type': 'partial', 'amountCents': 300, 'reason': '二次部分退款'}, token), 'second partial refund inside original window')
    expect(200, call('POST', '/internal/v1/aftersales/' + again['id'] + '/decision', {'decision': 'approve'}, key=internal_key), 'approve second partial refund')
    after_second = expect(200, call('GET', '/api/user/v1/orders/' + aftersale_order, token=token), 'first closure time is retained')
    assert after_second['firstClosedAt'] == near_deadline and after_second['refundedCents'] == 800, after_second
    time.sleep(5)
    expect(410, call('POST', '/api/user/v1/orders/' + aftersale_order + '/aftersales', {'type': 'partial', 'amountCents': 100, 'reason': '过期边界测试'}, token), 'second refund does not reset 24-hour window')
    for status, extra in [('商户出餐中', {}), ('已到暂存点 / 待领取', {}), ('配送中', {'riderName': '测试骑手'})]:
        expect(200, call('POST', '/internal/v1/orders/' + order_id + '/status', {'status': status, **extra}, key=internal_key), 'fulfillment: ' + status)
    expect(200, call('POST', '/internal/v1/orders/' + order_id + '/location', {'latitude': 31.2304, 'longitude': 121.4737, 'accuracyM': 12, 'riderName': '测试骑手'}, key=internal_key), 'publish delivery location')
    location = expect(200, call('GET', '/api/user/v1/orders/' + order_id + '/location', token=token), 'user reads location')
    assert location['available'] and location['state'] == 'live', location
    expect(200, call('POST', '/internal/v1/orders/' + order_id + '/status', {'status': '已完成'}, key=internal_key), 'complete delivery')
    ended = expect(200, call('GET', '/api/user/v1/orders/' + order_id + '/location', token=token), 'location removed after delivery')
    assert not ended['available'] and ended['state'] == 'ended', ended
    print('PASS: isolated login/address, single and multi-store checkout, refund cap and 24-hour window, independent merchant fulfillment, and expiring location')
except Exception:
    if not subprocess.run(['docker', 'inspect', user_container], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        logs = subprocess.run(['docker', 'logs', user_container], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        lines = logs.stdout.splitlines()
        matches = [index for index, line in enumerate(lines) if 'ERROR' in line or 'Caused by:' in line or 'DataAccessException' in line]
        selected = set()
        for index in matches:
            selected.update(range(max(0, index - 2), min(len(lines), index + 8)))
        print('--- user-api diagnostic log ---\n' + '\n'.join(lines[index] for index in sorted(selected)), file=sys.stderr)
    raise
finally:
    if created_user_api: subprocess.run(['docker', 'rm', '-f', user_container], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if created_admin: subprocess.run(['docker', 'rm', '-f', admin_container], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if created_db:
        try: root_sql('DROP DATABASE IF EXISTS `' + database + '`')
        except subprocess.CalledProcessError: print('Cleanup required for temporary database ' + database)
    if created_user:
        try: root_sql("DROP USER IF EXISTS '" + user + "'@'%'")
        except subprocess.CalledProcessError: print('Cleanup required for temporary database user ' + user)
