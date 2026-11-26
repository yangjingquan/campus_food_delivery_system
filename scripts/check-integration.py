#!/usr/bin/env python3
"""Run isolated integration checks against a temporary MySQL schema and backend container."""
import concurrent.futures
import datetime
import http.cookiejar
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
suffix=secrets.token_hex(5)
database='campus_it_'+suffix
user='it_'+suffix
password=secrets.token_hex(20)
admin_password=secrets.token_hex(16)
internal_key=secrets.token_hex(32)
container='campus-it-'+suffix
created_db=False
created_user=False
created_container=False

def run(args,**kwargs):
    return subprocess.run(args,cwd=ROOT,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout.strip()

def root_sql(sql):
    return run(['docker','compose','exec','-T','mysql','sh','-c','export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --default-character-set=utf8mb4 -uroot -N -e "$1"','_',sql])

def sql(statement): return root_sql('USE `'+database+'`; '+statement)

API = '/api/admin/v1'

def encrypt_password(path, payload):
    challenge = check(200, call('POST', API + '/auth/password-challenge'), 'password challenge')
    # Reuse the production Web Crypto client so the test covers interoperability.
    script = """
import { readFileSync } from 'node:fs'
import { encryptPasswordRequest } from './admin-web/src/api/passwordTransport.ts'
const { challenge, path, payload } = JSON.parse(readFileSync(0, 'utf8'))
console.log(JSON.stringify(await encryptPasswordRequest(challenge, path, payload)))
"""
    encrypted = run(['node', '--experimental-strip-types', '--input-type=module', '-e', script],
                    input=json.dumps({'challenge': challenge, 'path': path, 'payload': payload}))
    return json.loads(encrypted)

def password_path(path, body):
    return body is not None and 'challengeId' not in body and (
        path in ('/auth/login', '/auth/password', '/administrators')
        or (path.startswith('/administrators/') and path.endswith('/password'))
    )

def call(method,path,body=None,token=None,internal=False,protect=True):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if internal: headers['X-Internal-Key']=internal_key
    if protect and path.startswith(API) and password_path(path[len(API):], body):
        body = encrypt_password(path[len(API):], body)
    data=None if body is None else json.dumps(body).encode('utf-8')
    try:
        with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=5) as response:
            return response.status,json.loads(response.read() or b'{}')
    except urllib.error.HTTPError as e:
        return e.code,json.loads(e.read() or b'{}')

def check(expected,result,label):
    if result[0]!=expected: raise AssertionError(f'{label}: HTTP {result[0]}, expected {expected}; {result[1]}')
    return result[1]

def action(name,payload,token): return call('POST','/api/admin/v1/actions',{'type':name,'payload':payload},token)

try:
    root_sql("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4")
    created_db=True
    root_sql("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'")
    created_user=True
    root_sql("GRANT ALL PRIVILEGES ON `"+database+"`.* TO '"+user+"'@'%'")
    run(['docker','compose','run','-d','--no-deps','--name',container,'-p','127.0.0.1::8080',
         '-e','DB_URL=jdbc:mysql://mysql:3306/'+database+'?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
         '-e','DB_USER='+user,'-e','DB_PASSWORD='+password,'-e','ADMIN_USER=testadmin','-e','ADMIN_PASSWORD='+admin_password,
         '-e','INTERNAL_API_KEY='+internal_key,'-e','NACOS_SERVER_ADDR=nacos:8848','-e','USER_PAYMENT_MODE=wechat',
         '-e','REDIS_KEY_PREFIX=admin-it:'+suffix+':',
         '-e','SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false','admin-api'])
    created_container=True
    port=run(['docker','port',container,'8080/tcp']).split(':')[-1]
    base='http://127.0.0.1:'+port
    for _ in range(90):
        try:
            status, _ = call('POST', API + '/auth/password-challenge')
            if status == 200: break
        except (OSError,urllib.error.URLError): pass
        time.sleep(1)
    else: raise RuntimeError('isolated backend did not start')
    credentials = {'username': 'testadmin', 'password': admin_password}
    check(400, call('POST', API + '/auth/login', credentials, protect=False), 'plaintext login rejected')
    sealed = encrypt_password('/auth/login', credentials)
    data = check(200, call('POST', API + '/auth/login', sealed), 'encrypted login')
    check(400, call('POST', API + '/auth/login', sealed), 'login ciphertext replay rejected')
    tampered = encrypt_password('/auth/login', credentials)
    tampered['ciphertext'] = ('A' if tampered['ciphertext'][0] != 'A' else 'B') + tampered['ciphertext'][1:]
    check(400, call('POST', API + '/auth/login', tampered), 'tampered ciphertext rejected')
    wrong_path = encrypt_password('/auth/password', credentials)
    check(400, call('POST', API + '/auth/login', wrong_path), 'ciphertext scoped to endpoint')
    token=data['token']
    assert 'admin.manage' in data['user']['permissions']
    assert sql("SELECT COUNT(*) FROM admin_user WHERE username='testadmin' AND password_hash LIKE '$2%'") == '1'
    browser_cookies = http.cookiejar.CookieJar()
    browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(browser_cookies))

    def browser_call(method, path, body=None, headers=None):
        return browser.open(urllib.request.Request(base + '/api/admin/v1' + path,
            data=None if body is None else json.dumps(body).encode('utf-8'),
            headers={'Content-Type': 'application/json', **(headers or {})}, method=method), timeout=5)

    browser_headers = {'X-Admin-Session': '1'}
    with browser_call('POST', '/auth/login', encrypt_password('/auth/login', credentials), browser_headers) as response:
        cookie = response.headers['Set-Cookie']
        assert all(flag in cookie for flag in ('Max-Age=172800', 'HttpOnly', 'SameSite=Strict', 'Path=/api/admin/v1'))
        assert 'token' not in json.load(response)
    browser_token = next(item.value for item in browser_cookies if item.name == 'campus_admin_session')
    with browser_call('GET', '/auth/session', headers=browser_headers) as response:
        assert json.load(response)['permissions'] == data['user']['permissions']
    with browser_call('GET', '/overview', headers=browser_headers) as response:
        assert response.status == 200
    try:
        browser_call('POST', '/auth/logout', {})
    except urllib.error.HTTPError as error:
        assert error.code == 401
    else:
        raise AssertionError('Cookie request without the browser header should be rejected')
    with browser_call('POST', '/auth/logout', {}, browser_headers) as response:
        assert 'Max-Age=0' in response.headers['Set-Cookie']
    assert not list(browser_cookies)
    check(401, call('GET', '/api/admin/v1/auth/session', token=browser_token), 'revoked browser session')
    check(401,call('POST','/internal/v1/withdrawals',{'accountId':'x','requestKey':'test-key1','amountCents':1}),'internal authentication')
    check(200,call('POST','/api/admin/v1/administrators',{'username':'test_ops','password':'test-password-123','role':'operations'},token),'create operations')
    check(409,call('POST','/api/admin/v1/administrators',{'username':'test_ops','password':'test-password-123','role':'operations'},token),'duplicate administrator')
    ops=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_ops','password':'test-password-123'}),'operations login')['token']
    check(403,call('GET','/api/admin/v1/workspace?section=finance',token=ops),'finance forbidden')
    check(403,action('wechat.save-app',{'version':1,'appType':'user','appId':'','appSecret':''},ops),'wechat forbidden')
    merchants=check(200,call('GET','/api/admin/v1/workspace?section=merchants',token=ops),'operations read')['rows']
    assert 'auditReason' in merchants[0] and merchants[0]['auditReason'] is None
    check(200,call('POST','/api/admin/v1/auth/password',{'oldPassword':'test-password-123','newPassword':'replacement-password-456'},ops),'change password')
    check(401,call('GET','/api/admin/v1/workspace?section=merchants',token=ops),'old session revoked')
    ops_new=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_ops','password':'replacement-password-456'}),'new password login')['token']
    check(200,call('POST','/api/admin/v1/administrators',{'username':'test_read','password':'test-password-123','role':'readonly'},token),'create readonly')
    readonly=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_read','password':'test-password-123'}),'readonly login')['token']
    check(200,call('GET','/api/admin/v1/workspace?section=finance',token=readonly),'readonly finance')
    check(403,action('merchant.audit',{'id':'M01','decision':'approve'},readonly),'readonly cannot audit')
    overview=check(200,call('GET','/api/admin/v1/overview',token=token),'overview after layer refactor')
    assert overview['todoMerchants']==1 and overview['todoRiders']==1
    for section in ('orders','riders','aftersales','withdrawals','config'):
        check(200,call('GET','/api/admin/v1/workspace?section='+section,token=token),'workspace '+section)
    check(200,action('merchant.audit',{'id':'M01','decision':'approve'},token),'merchant audit')
    assert sql("SELECT audit_status FROM merchant WHERE id='M01'")=='APPROVED'
    check(200,action('rider.audit',{'id':'R02','decision':'reject','reason':'资料不完整'},token),'rider audit')
    assert sql("SELECT CONCAT(audit_status,':',audit_reason) FROM rider WHERE id='R02'")=='REJECTED:资料不完整'
    config=check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'current config')['config']
    check(200,action('config.save',{'patch':config},token),'save operations config')
    assert sql('SELECT COUNT(*) FROM operations_config')=='2'
    config_state=check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'peak migration')
    assert config_state['config']['peakPeriods']==[{'start':'11:30','end':'13:30'}]
    original_version=config_state['config']['version']
    config=config_state['config']
    config['peakPeriods']=[{'start':'17:00','end':'19:00'},{'start':'11:30','end':'13:30'}]
    check(200,action('config.save',{'patch':config},token),'multiple peak periods')
    config_state=check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'multiple peaks readback')
    config=config_state['config']
    assert config['peakPeriods']==[{'start':'11:30','end':'13:30'},{'start':'17:00','end':'19:00'}]
    assert next(row for row in config_state['history'] if row['version']==original_version)['peakPeriods']==[{'start':'11:30','end':'13:30'}]
    for periods in (
        [{'start':'11:30','end':'13:30'},{'start':'13:00','end':'14:00'}],
        [{'start':'09:00','end':'11:00'}],
        [{'start':'19:00','end':'18:00'}],
    ):
        check(400,action('config.save',{'patch':{**config,'peakPeriods':periods}},token),'invalid peaks rejected')
    config['peakPeriods']=[]
    check(200,action('config.save',{'patch':config},token),'remove all peak periods')
    config=check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'no peaks readback')['config']
    assert config['peakPeriods']==[]
    assert config['peakStart']==config['peakEnd']
    legacy={key:value for key,value in config.items() if key!='peakPeriods'}
    legacy.update(peakStart='12:00',peakEnd='13:00')
    check(200,action('config.save',{'patch':legacy},token),'legacy peak payload')
    assert check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'legacy peak readback')['config']['peakPeriods']==[{'start':'12:00','end':'13:00'}]
    check(200,call('POST','/api/admin/v1/administrators',{'username':'test_finance','password':'test-password-123','role':'finance'},token),'create finance')
    finance=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_finance','password':'test-password-123'}),'finance login')['token']
    check(200,call('GET','/api/admin/v1/workspace?section=finance',token=finance),'finance read')
    check(403,call('GET','/api/admin/v1/workspace?section=wechat',token=finance),'finance cannot view wechat')
    users=check(200,call('GET','/api/admin/v1/administrators',token=token),'administrators')['users']
    me=next(x for x in users if x['username']=='testadmin')
    check(409,call('PUT','/api/admin/v1/administrators/'+str(me['id'])+'/access',{'role':'readonly','enabled':True},token),'last super')
    read_id=next(x['id'] for x in users if x['username']=='test_read')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(read_id)+'/access',{'role':'operations','enabled':True},token),'replace administrator role')
    check(401, call('GET', API + '/workspace?section=finance', token=readonly), 'role update revokes old session')
    readonly = check(200, call('POST', API + '/auth/login', {'username': 'test_read', 'password': 'test-password-123'}), 'updated role login')['token']
    check(403, call('GET', API + '/workspace?section=finance', token=readonly), 'removed permission denied')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(read_id)+'/access',{'role':'operations','enabled':False},token),'disable administrator')
    check(401,call('GET','/api/admin/v1/workspace?section=merchants',token=readonly),'disabled session rejected')
    ops_id=next(x['id'] for x in users if x['username']=='test_ops')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(ops_id)+'/password',{'password':'reset-password-789'},token),'administrator password reset')
    check(401,call('GET','/api/admin/v1/workspace?section=merchants',token=ops_new),'reset session revoked')
    # Custom roles are additive and never grant access to the administrator control plane.
    role_data = {'name': '测试订单角色', 'description': '订单协同', 'permissions': ['order.coordinate']}
    check(403, call('POST', API + '/roles', role_data, finance), 'ordinary role cannot create roles')
    check(400, call('POST', API + '/roles', {**role_data, 'permissions': ['admin.manage']}, token), 'management permission reserved')
    check(409, call('PUT', API + '/roles/super', role_data, token), 'super role immutable')
    role_code = check(200, call('POST', API + '/roles', role_data, token), 'create custom role')['code']
    role_url = API + '/roles/' + role_code
    custom_credentials = {'username': 'test_custom', 'password': '测试密码-cipher-2026'}
    custom_access = {**custom_credentials, 'roleCodes': [role_code, 'finance']}
    check(400, call('POST', API + '/administrators', custom_access, token, protect=False), 'plaintext admin creation rejected')
    check(200, call('POST', API + '/administrators', custom_access, token), 'assign multiple roles')
    custom = check(200, call('POST', API + '/auth/login', custom_credentials), 'custom account login')
    assert {'order.view', 'order.coordinate', 'finance.view'} <= set(custom['user']['permissions'])
    assert 'overview.view' in custom['user']['permissions'] and 'admin.manage' not in custom['user']['permissions']
    check(403, call('GET', API + '/administrators/audit', token=custom['token']), 'audit access restricted')
    check(409, call('DELETE', role_url, token=token), 'assigned role protected')
    check(200, call('PUT', role_url, {**role_data, 'permissions': ['user.view']}, token), 'change role permissions')
    check(401, call('GET', API + '/auth/session', token=custom['token']), 'role permission update revokes sessions')
    custom = check(200, call('POST', API + '/auth/login', custom_credentials), 'relogin after role edit')
    assert {'user.view', 'finance.view'} <= set(custom['user']['permissions'])
    assert 'order.coordinate' not in custom['user']['permissions']
    access = check(200, call('GET', API + '/administrators', token=token), 'updated role and member list')
    custom_row = next(row for row in access['users'] if row['username'] == 'test_custom')
    assert set(custom_row['roleCodes']) == {role_code, 'finance'}
    check(200, call('PUT', API + f"/administrators/{custom_row['id']}/access",
                    {'roleCodes': ['finance'], 'enabled': True}, token), 'unassign custom role')
    check(200, call('DELETE', role_url, token=token), 'delete unassigned role')
    events = check(200, call('GET', API + '/administrators/audit', token=token), 'administrator audit')
    assert {'admin.role.create', 'admin.role.update', 'admin.role.delete', 'admin.password-reset'} <= {
        event['action'] for event in events
    }
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('MTEST','测试商户','MERCHANT',10000,'ACCOUNT_ACTIVE'),('RTEST','测试骑手','RIDER',10000,'ACCOUNT_ACTIVE'); INSERT INTO finance_debt(id,order_id,merchant,rider,rider_due_cents,merchant_balance_cents,platform_advance_cents,recovered_cents,merchant_account_id) VALUES('DTEST','OTEST','测试商户','测试骑手',3000,10000,3000,0,'MTEST');")
    p={'accountId':'MTEST','requestKey':'test-debt-0001','amountCents':8000}
    first=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'debt offset')
    assert first['status']=='INSUFFICIENT_FUNDS' and first['recoveredCents']==3000 and first['availableCents']==7000,first
    again=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'idempotent retry')
    assert again['recoveredCents']==3000 and sql("SELECT balance_cents FROM finance_account WHERE id='SYSTEM_PLATFORM'")=='3000'
    p={'accountId':'MTEST','requestKey':'test-freeze-001','amountCents':7000}
    freeze=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'freeze'); assert freeze['status']=='ACCEPTED'
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='MTEST'")=='0,7000'
    wid=freeze['withdrawalId'];check(200,action('withdrawal.review',{'id':wid,'decision':'reject','reason':'测试拒绝'},token),'reject')
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='MTEST'")=='7000,0'
    check(409,action('withdrawal.review',{'id':wid,'decision':'reject','reason':'重复'},token),'repeat review')
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('MMULTI','多笔欠款测试','MERCHANT',10000,'ACCOUNT_ACTIVE'); "
        "INSERT INTO finance_debt(id,order_id,merchant,rider,rider_due_cents,merchant_balance_cents,platform_advance_cents,recovered_cents,merchant_account_id) VALUES "
        "('D01','O1','多笔欠款测试','骑手',3000,10000,3000,1000,'MMULTI'),"
        "('D02','O2','多笔欠款测试','骑手',2000,10000,2000,2000,'MMULTI'),"
        "('D03','O3','多笔欠款测试','骑手',2000,10000,2000,0,'MMULTI')")
    multi=check(200,call('POST','/internal/v1/withdrawals',{'accountId':'MMULTI','requestKey':'test-multi-debt','amountCents':5000},internal=True),'multiple debts')
    assert multi['recoveredCents']==4000 and multi['availableCents']==1000
    assert sql("SELECT GROUP_CONCAT(CONCAT(id,':',recovered_cents) ORDER BY id SEPARATOR ',') FROM finance_debt WHERE merchant_account_id='MMULTI'")=='D01:3000,D02:2000,D03:2000'
    assert sql("SELECT COUNT(*) FROM account_journal WHERE account_id='MMULTI' AND event_type='debt_recover'")=='2'
    check(200,action('withdrawal.review',{'id':multi['withdrawalId'],'decision':'reject','reason':'测试拒绝'},token),'unfreeze after debt recovery')
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='MMULTI'")=='6000,0'
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('RERR','冻结异常测试','RIDER',1000,'ACCOUNT_ACTIVE')")
    guarded=check(200,call('POST','/internal/v1/withdrawals',{'accountId':'RERR','requestKey':'test-guarded-001','amountCents':1000},internal=True),'guarded withdrawal')
    sql("UPDATE finance_account SET frozen_cents=0 WHERE id='RERR'")
    check(409,action('withdrawal.review',{'id':guarded['withdrawalId'],'decision':'reject','reason':'测试冻结余额不足'},token),'insufficient frozen balance')
    assert sql("SELECT status FROM withdrawal_request WHERE id='"+guarded['withdrawalId']+"'")=='PENDING_REVIEW'
    assert sql("SELECT balance_cents FROM finance_account WHERE id='RERR'")=='0'
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('RIDEMP','幂等并发测试','RIDER',6000,'ACCOUNT_ACTIVE')")
    idem_payload={'accountId':'RIDEMP','requestKey':'test-idempotent-concurrent','amountCents':5000}
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        retries=list(pool.map(lambda _: check(200,call('POST','/internal/v1/withdrawals',idem_payload,internal=True),'concurrent identical request'),range(4)))
    assert all(item['status']=='ACCEPTED' for item in retries) and len({item['withdrawalId'] for item in retries})==1,retries
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RIDEMP'")=='1000,5000'
    assert sql("SELECT COUNT(*) FROM withdrawal_request WHERE account_id='RIDEMP'")=='1'
    pending_rows=check(200,call('GET','/api/admin/v1/workspace?section=withdrawals',token=finance),'reviewable pending withdrawal')['rows']
    pending_row=next(row for row in pending_rows if row['id']==retries[0]['withdrawalId'])
    assert pending_row['status']=='PENDING_REVIEW' and pending_row['reviewable']==1
    check(409,call('POST','/internal/v1/withdrawals',{'accountId':'RIDEMP','requestKey':'test-idempotent-concurrent','amountCents':1000},internal=True),'idempotency payload conflict')
    def withdraw(n):return call('POST','/internal/v1/withdrawals',{'accountId':'RTEST','requestKey':'test-rider-00'+str(n),'amountCents':8000},internal=True)[1]
    with concurrent.futures.ThreadPoolExecutor(2) as pool: outcomes=list(pool.map(withdraw,[1,2]))
    assert sorted(x['status'] for x in outcomes)==['ACCEPTED','INSUFFICIENT_FUNDS'],outcomes
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='2000,8000'
    riderwid=next(x['withdrawalId'] for x in outcomes if x['status']=='ACCEPTED')
    check(200,action('withdrawal.review',{'id':riderwid,'decision':'approve'},finance),'finance review')
    assert sql("SELECT status FROM withdrawal_request WHERE id='"+riderwid+"'")=='PENDING_PAYOUT'
    assert sql("SELECT status FROM withdrawal_intent WHERE withdrawal_id='"+riderwid+"'")=='ACCEPTED'
    withdrawals=check(200,call('GET','/api/admin/v1/workspace?section=withdrawals',token=finance),'withdrawal labels')['rows']
    assert next(row for row in withdrawals if row['id']==riderwid)['status']=='PENDING_PAYOUT'
    sql("UPDATE withdrawal_request SET status='PENDING_REVIEW' WHERE id='"+riderwid+"'")
    withdrawals=check(200,call('GET','/api/admin/v1/workspace?section=withdrawals',token=finance),'manually reset withdrawal')['rows']
    reset_row=next(row for row in withdrawals if row['id']==riderwid)
    assert reset_row['status']=='PENDING_REVIEW' and reset_row['reviewable']==0
    check(409,action('withdrawal.review',{'id':riderwid,'decision':'approve'},finance),'already approved cannot be approved again')
    check(409,action('withdrawal.review',{'id':riderwid,'decision':'reject','reason':'测试'},finance),'already approved cannot be rejected after a manual reset')
    assert sql("SELECT frozen_cents FROM finance_account WHERE id='RTEST'")=='8000'
    sql("UPDATE withdrawal_request SET status='PENDING_PAYOUT' WHERE id='"+riderwid+"'")
    path='/internal/v1/withdrawals/'+riderwid+'/transfer/'
    prepared=check(200,call('POST',path+'prepare',{},internal=True),'prepare')
    again=check(200,call('POST',path+'prepare',{},internal=True),'prepare replay')
    assert prepared['transferId']==again['transferId']
    assert sql("SELECT status FROM withdrawal_transfer WHERE withdrawal_id='"+riderwid+"'")=='PROCESSING'
    check(200,call('POST',path+'result',{'outcome':'unknown','reason':'网络超时'},internal=True),'unknown')
    assert sql("SELECT status FROM withdrawal_transfer WHERE withdrawal_id='"+riderwid+"'")=='UNKNOWN'
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='2000,8000'
    failed={'outcome':'failure','channelReference':'verified-test-channel-1','reason':'渠道明确失败'}
    check(200,call('POST',path+'result',failed,internal=True),'failure')
    assert sql("SELECT status FROM withdrawal_request WHERE id='"+riderwid+"'")=='PAYOUT_FAILED'
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='10000,0'
    check(200,call('POST',path+'result',failed,internal=True),'failure replay')
    check(409,call('POST',path+'result',{'outcome':'success','channelReference':'verified-test-channel-1'},internal=True),'terminal conflict')
    second=check(200,call('POST','/internal/v1/withdrawals',{'accountId':'RTEST','requestKey':'test-rider-success','amountCents':8000},internal=True),'second freeze')
    check(200,action('withdrawal.review',{'id':second['withdrawalId'],'decision':'approve'},finance),'second approve')
    secondpath='/internal/v1/withdrawals/'+second['withdrawalId']+'/transfer/'
    check(200,call('POST',secondpath+'prepare',{},internal=True),'second prepare')
    success={'outcome':'success','channelReference':'verified-test-channel-2'}
    check(200,call('POST',secondpath+'result',success,internal=True),'success result')
    check(200,call('POST',secondpath+'result',success,internal=True),'success replay')
    assert sql("SELECT CONCAT(w.status,',',t.status) FROM withdrawal_request w JOIN withdrawal_transfer t ON t.withdrawal_id=w.id WHERE w.id='"+second['withdrawalId']+"'")=='PAID,SUCCEEDED'
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='2000,0'
    wx=check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'wechat read')['config']
    assert wx['enabled'] is True
    assert sql("SELECT payment_enabled FROM wechat_config ORDER BY id DESC LIMIT 1")=='0'
    secret='0123456789abcdef0123456789abcdef'
    check(200,action('wechat.save-app',{'version':wx['version'],'appType':'user','appId':'wx0123456789abcdef','appSecret':secret},token),'save secret')
    state=check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'wechat read back')
    assert state['config']['userSecretSet'] and secret not in json.dumps(state)
    api_time=datetime.datetime.fromisoformat(state['config']['createdAt'].replace('Z','+00:00')).timestamp()
    db_time=int(sql('SELECT UNIX_TIMESTAMP(created_at) FROM wechat_config ORDER BY id DESC LIMIT 1'))
    assert abs(api_time-db_time)<1,(api_time,db_time)
    assert sql("SELECT COUNT(*) FROM wechat_secret WHERE purpose='user_appsecret' AND HEX(ciphertext) LIKE '%"+secret.encode().hex().upper()+"%'")=='0'
    oldversion=state['config']['version']
    sql("UPDATE wechat_secret SET key_fingerprint=NULL WHERE purpose='user_appsecret'")
    check(400,action('wechat.save-app',{'version':oldversion,'appType':'merchant','appId':'wx0123456789abcdee','appSecret':secret},token),'legacy app configuration is read-only')
    check(400,action('wechat.save-app',{'version':oldversion,'appType':'user','appId':'wx0123456789abcdee','appSecret':''},token),'new AppID requires matching secret')
    check(200,action('wechat.save-app',{'version':oldversion,'appType':'user','appId':'wx0123456789abcdef','appSecret':'abcdef0123456789abcdef0123456789'},token),'legacy key fingerprint')
    assert sql('SELECT COUNT(*) FROM wechat_secret WHERE key_fingerprint IS NULL')=='0'
    oldversion=check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'wechat version')['config']['version']
    private_key=subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048'],check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL).stdout
    public_key=subprocess.run(['openssl','pkey','-pubout'],input=private_key,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL).stdout
    check(200,action('wechat.save-payment',{'version':oldversion,'apiV3Key':'0123456789abcdef0123456789abcdef','merchantPrivateKey':private_key,'wechatPublicKey':public_key},token),'save multiple secrets in one transaction')
    secret_purposes=sql("SELECT CONCAT_WS(',',a.purpose,b.purpose,c.purpose) FROM wechat_config w JOIN wechat_secret a ON a.id=w.api_v3_secret_id JOIN wechat_secret b ON b.id=w.merchant_private_secret_id JOIN wechat_secret c ON c.id=w.wechat_public_secret_id ORDER BY w.id DESC LIMIT 1")
    assert secret_purposes=='api_v3,merchant_private,wechat_public',secret_purposes
    oldversion=check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'wechat version after payment')['config']['version']
    check(400,action('wechat.save-payment',{'version':oldversion,'apiV3Key':'short'},token),'invalid key')
    assert check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'no change')['config']['version']==oldversion
    check(404,action('wechat.toggle',{'version':oldversion,'enabled':True},token),'removed payment toggle')
    check(200,action('wechat.rollback',{'version':oldversion,'targetVersion':1,'reason':'验证历史回滚'},token),'wechat rollback')
    state=check(200,call('GET','/api/admin/v1/workspace?section=wechat',token=token),'wechat rollback read')
    assert state['config']['enabled'] is True and not state['config']['userSecretSet'] and len(state['history'])>=2
    check(200,call('POST','/api/admin/v1/administrators',{'username':'test_print_read','password':'test-password-123','role':'readonly'},token),'create printing settings viewer')
    printing_viewer=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_print_read','password':'test-password-123'}),'printing settings viewer login')['token']
    config_path='/api/admin/v1/workspace?section=config'
    printing_state=check(200,call('GET',config_path,token=token),'printing settings initial state')
    assert printing_state['printing']=={'appId':'','appSecretSet':False}
    operations_version=printing_state['config']['version']
    printer_secret=secrets.token_hex(24)
    printer={'appId':'test-printer','appSecret':printer_secret}
    check(403,action('config.save-printing',printer,printing_viewer),'read-only printing settings')
    check(400,action('config.save-printing',{'appId':printer['appId']},token),'initial printer secret required')
    saved=check(200,action('config.save-printing',printer,token),'save printing settings')
    assert saved=={'appId':printer['appId'],'appSecretSet':True}
    check(200,action('config.save-printing',{'appId':printer['appId'],'appSecret':''},token),'keep existing printer secret')
    check(400,action('config.save-printing',{'appId':'replacement-printer'},token),'new AppID requires new secret')
    printing_state=check(200,call('GET',config_path,token=printing_viewer),'printing settings readback')
    assert printing_state['printing']==saved and printing_state['config']['version']==operations_version
    assert printer_secret not in json.dumps(printing_state)
    assert sql("SELECT COUNT(*) FROM wechat_secret WHERE purpose='printer_appsecret' AND OCTET_LENGTH(ciphertext)="+str(len(printer_secret)+16)+" AND HEX(ciphertext) NOT LIKE '%"+printer_secret.encode().hex().upper()+"%'")=='1'
    rotated=check(200,action('config.save-printing',{'appId':'replacement-printer','appSecret':secrets.token_hex(24)},token),'rotate printer credentials')
    assert rotated=={'appId':'replacement-printer','appSecretSet':True}
    assert sql("SELECT COUNT(*) FROM wechat_secret WHERE purpose='printer_appsecret'")=='1'
    assert sql("SELECT COUNT(*) FROM admin_audit_log WHERE action_name='config.save-printing'")=='2'
    merchant_printer_path='/api/admin/v1/merchants/{}/printer'
    merchant_pkey=secrets.token_hex(24)
    merchant_printer={'id':'M01','sn':'SN-FIRST','pkey':merchant_pkey}
    check(401,action('merchant.save-printer',merchant_printer,None),'printer save requires admin session')
    check(403,action('merchant.save-printer',merchant_printer,printing_viewer),'read-only merchant printer forbidden')
    check(403,call('GET',merchant_printer_path.format('M01'),token=printing_viewer),'read-only merchant printer read forbidden')
    check(404,action('merchant.save-printer',{**merchant_printer,'id':'missing-merchant'},token),'merchant must exist')
    check(400,action('merchant.save-printer',{**merchant_printer,'pkey':''},token),'initial merchant PKey required')
    assert check(200,action('merchant.save-printer',merchant_printer,token),'save first merchant printer')=={'sn':'SN-FIRST','pkeySet':True}
    assert check(200,call('GET',merchant_printer_path.format('M02'),token=token),'second merchant independent')=={'sn':'','pkeySet':False}
    check(200,action('merchant.save-printer',{'id':'M02','sn':'SN-SECOND','pkey':secrets.token_hex(24)},token),'save second merchant printer')
    assert check(200,call('GET',merchant_printer_path.format('M01'),token=token),'read first merchant printer')=={'sn':'SN-FIRST','pkeySet':True}
    check(400,action('merchant.save-printer',{'id':'M01','sn':'SN-NEW'},token),'changing SN requires PKey')
    check(200,action('merchant.save-printer',{'id':'M01','sn':'SN-FIRST'},token),'retain existing PKey')
    assert sql("SELECT OCTET_LENGTH(ciphertext) FROM wechat_secret s JOIN merchant_printer_settings p ON p.pkey_secret_id=s.id WHERE p.merchant_id='M01'")==str(len(merchant_pkey)+16)
    check(200,action('merchant.save-printer',{'id':'M01','sn':'SN-NEW','pkey':secrets.token_hex(24)},token),'replace merchant PKey')
    assert sql("SELECT COUNT(*) FROM wechat_secret WHERE purpose='merchant_printer_pkey'")=='2'
    print('PASS: encrypted credentials, RBAC, session revocation, last-super protection, debt recovery, idempotency, freeze/unfreeze, transfer failure, concurrency and encrypted secrets')
except BaseException:
    if created_container:
        logs=subprocess.run(['docker','logs',container],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True).stdout
        for line in logs.splitlines():
            if any(term in line for term in ('Deadlock found', 'Lock wait timeout', 'CannotAcquireLockException', 'DeadlockLoserDataAccessException')):
                print(line, file=sys.stderr)
    raise
finally:
    if created_container: subprocess.run(['docker','rm','-f',container],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if created_db or created_user:
        try:
            if created_db: root_sql("DROP DATABASE IF EXISTS `"+database+"`")
            if created_user: root_sql("DROP USER IF EXISTS '"+user+"'@'%'")
        except subprocess.CalledProcessError: print('Cleanup required for temporary database/user '+database+' / '+user,file=sys.stderr)
