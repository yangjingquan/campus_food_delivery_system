#!/usr/bin/env python3
"""Run isolated integration checks against a temporary MySQL schema and backend container."""
import concurrent.futures
import datetime
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

def call(method,path,body=None,token=None,internal=False):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if internal: headers['X-Internal-Key']=internal_key
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
         '-e','SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false','admin-api'])
    created_container=True
    port=run(['docker','port',container,'8080/tcp']).split(':')[-1]
    base='http://127.0.0.1:'+port
    for _ in range(40):
        try:
            status, data=call('POST','/api/admin/v1/auth/login',{'username':'testadmin','password':admin_password})
            if status==200: break
        except (OSError,urllib.error.URLError): pass
        time.sleep(1)
    else: raise RuntimeError('isolated backend did not start')
    token=data['token']
    assert 'admin.manage' in data['user']['permissions']
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
    assert sql("SELECT audit_status FROM merchant WHERE id='M01'")=='已通过'
    check(200,action('rider.audit',{'id':'R02','decision':'reject','reason':'资料不完整'},token),'rider audit')
    assert sql("SELECT CONCAT(audit_status,':',audit_reason) FROM rider WHERE id='R02'")=='已驳回:资料不完整'
    config=check(200,call('GET','/api/admin/v1/workspace?section=config',token=token),'current config')['config']
    check(200,action('config.save',{'patch':config},token),'save operations config')
    assert sql('SELECT COUNT(*) FROM operations_config')=='2'
    check(200,call('POST','/api/admin/v1/administrators',{'username':'test_finance','password':'test-password-123','role':'finance'},token),'create finance')
    finance=check(200,call('POST','/api/admin/v1/auth/login',{'username':'test_finance','password':'test-password-123'}),'finance login')['token']
    check(200,call('GET','/api/admin/v1/workspace?section=finance',token=finance),'finance read')
    check(403,call('GET','/api/admin/v1/workspace?section=wechat',token=finance),'finance cannot view wechat')
    users=check(200,call('GET','/api/admin/v1/administrators',token=token),'administrators')['users']
    me=next(x for x in users if x['username']=='testadmin')
    check(409,call('PUT','/api/admin/v1/administrators/'+str(me['id'])+'/access',{'role':'readonly','enabled':True},token),'last super')
    read_id=next(x['id'] for x in users if x['username']=='test_read')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(read_id)+'/access',{'role':'operations','enabled':True},token),'replace administrator role')
    check(403,call('GET','/api/admin/v1/workspace?section=finance',token=readonly),'role update applied')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(read_id)+'/access',{'role':'operations','enabled':False},token),'disable administrator')
    check(401,call('GET','/api/admin/v1/workspace?section=merchants',token=readonly),'disabled session rejected')
    ops_id=next(x['id'] for x in users if x['username']=='test_ops')
    check(200,call('PUT','/api/admin/v1/administrators/'+str(ops_id)+'/password',{'password':'reset-password-789'},token),'administrator password reset')
    check(401,call('GET','/api/admin/v1/workspace?section=merchants',token=ops_new),'reset session revoked')
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('MTEST','测试商户','商户',10000,'正常'),('RTEST','测试骑手','骑手',10000,'正常'); INSERT INTO finance_debt(id,order_id,merchant,rider,rider_due_cents,merchant_balance_cents,platform_advance_cents,recovered_cents,merchant_account_id) VALUES('DTEST','OTEST','测试商户','测试骑手',3000,10000,3000,0,'MTEST');")
    p={'accountId':'MTEST','requestKey':'test-debt-0001','amountCents':8000}
    first=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'debt offset')
    assert first['status']=='余额不足' and first['recoveredCents']==3000 and first['availableCents']==7000,first
    again=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'idempotent retry')
    assert again['recoveredCents']==3000 and sql("SELECT balance_cents FROM finance_account WHERE id='SYSTEM_PLATFORM'")=='3000'
    p={'accountId':'MTEST','requestKey':'test-freeze-001','amountCents':7000}
    freeze=check(200,call('POST','/internal/v1/withdrawals',p,internal=True),'freeze'); assert freeze['status']=='已受理'
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='MTEST'")=='0,7000'
    wid=freeze['withdrawalId'];check(200,action('withdrawal.review',{'id':wid,'decision':'reject','reason':'测试拒绝'},token),'reject')
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='MTEST'")=='7000,0'
    check(409,action('withdrawal.review',{'id':wid,'decision':'reject','reason':'重复'},token),'repeat review')
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('MMULTI','多笔欠款测试','商户',10000,'正常'); "
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
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('RERR','冻结异常测试','骑手',1000,'正常')")
    guarded=check(200,call('POST','/internal/v1/withdrawals',{'accountId':'RERR','requestKey':'test-guarded-001','amountCents':1000},internal=True),'guarded withdrawal')
    sql("UPDATE finance_account SET frozen_cents=0 WHERE id='RERR'")
    check(409,action('withdrawal.review',{'id':guarded['withdrawalId'],'decision':'reject','reason':'测试冻结余额不足'},token),'insufficient frozen balance')
    assert sql("SELECT status FROM withdrawal_request WHERE id='"+guarded['withdrawalId']+"'")=='待审核'
    assert sql("SELECT balance_cents FROM finance_account WHERE id='RERR'")=='0'
    sql("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES('RIDEMP','幂等并发测试','骑手',6000,'正常')")
    idem_payload={'accountId':'RIDEMP','requestKey':'test-idempotent-concurrent','amountCents':5000}
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        retries=list(pool.map(lambda _: check(200,call('POST','/internal/v1/withdrawals',idem_payload,internal=True),'concurrent identical request'),range(4)))
    assert all(item['status']=='已受理' for item in retries) and len({item['withdrawalId'] for item in retries})==1,retries
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RIDEMP'")=='1000,5000'
    assert sql("SELECT COUNT(*) FROM withdrawal_request WHERE account_id='RIDEMP'")=='1'
    check(409,call('POST','/internal/v1/withdrawals',{'accountId':'RIDEMP','requestKey':'test-idempotent-concurrent','amountCents':1000},internal=True),'idempotency payload conflict')
    def withdraw(n):return call('POST','/internal/v1/withdrawals',{'accountId':'RTEST','requestKey':'test-rider-00'+str(n),'amountCents':8000},internal=True)[1]
    with concurrent.futures.ThreadPoolExecutor(2) as pool: outcomes=list(pool.map(withdraw,[1,2]))
    assert sorted(x['status'] for x in outcomes)==['余额不足','已受理'],outcomes
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='2000,8000'
    riderwid=next(x['withdrawalId'] for x in outcomes if x['status']=='已受理')
    check(200,action('withdrawal.review',{'id':riderwid,'decision':'approve'},finance),'finance review')
    path='/internal/v1/withdrawals/'+riderwid+'/transfer/'
    prepared=check(200,call('POST',path+'prepare',{},internal=True),'prepare')
    again=check(200,call('POST',path+'prepare',{},internal=True),'prepare replay')
    assert prepared['transferId']==again['transferId']
    check(200,call('POST',path+'result',{'outcome':'unknown','reason':'网络超时'},internal=True),'unknown')
    assert sql("SELECT CONCAT(balance_cents,',',frozen_cents) FROM finance_account WHERE id='RTEST'")=='2000,8000'
    failed={'outcome':'failure','channelReference':'verified-test-channel-1','reason':'渠道明确失败'}
    check(200,call('POST',path+'result',failed,internal=True),'failure')
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
    check(200,action('wechat.save-app',{'version':oldversion,'appType':'merchant','appId':'wx0123456789abcdee','appSecret':'abcdef0123456789abcdef0123456789'},token),'legacy key fingerprint')
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
    print('PASS: RBAC, session revocation, last-super protection, debt recovery, idempotency, freeze/unfreeze, transfer failure, concurrency and encrypted secrets')
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
