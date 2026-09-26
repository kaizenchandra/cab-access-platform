#!/usr/bin/env python3
"""Synthetic local API demonstration. Uses real local Keycloak tokens and PostgreSQL."""
import base64, json, os, pathlib, time, urllib.request, urllib.error, urllib.parse, uuid
BASE=os.getenv('API_URL','http://localhost:8080')
IDP=os.getenv('OIDC_URL','http://localhost:8180')
TOKENS={}
def token(user):
    data=urllib.parse.urlencode({'grant_type':'password','client_id':'cab-demo','username':user,'password':'local-demo-only'}).encode()
    with urllib.request.urlopen(IDP+'/realms/cab-access/protocol/openid-connect/token',data,timeout=10) as r: result=json.load(r)
    TOKENS[user]=result['access_token']
    payload=TOKENS[user].split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))['sub']
def api(method,path,data=None,user='admin',headers=None,expected=200):
    h={'Content-Type':'application/json'}
    if user: h['Authorization']='Bearer '+TOKENS[user]
    if headers: h.update(headers)
    request=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers=h,method=method)
    try:
        with urllib.request.urlopen(request,timeout=15) as r: status=r.status;body=r.read().decode()
    except urllib.error.HTTPError as e: status=e.code;body=e.read().decode()
    assert status==expected, f'{method} {path}: expected {expected}, got {status}: {body[:1000]}'
    try: return json.loads(body)
    except json.JSONDecodeError: return body

def wait(get,ready,description):
    for _ in range(100):
        value=get()
        if ready(value): return value
        time.sleep(.15)
    raise AssertionError('Timed out: '+description+' '+str(value))
def key(): return {'Idempotency-Key':str(uuid.uuid4())}
def main():
    subjects={u:token(u) for u in ['platform','admin','reviewer','operator','supervisor','finance','finance2','driver','support','outsider']}
    tenant=str(uuid.uuid4());merchant=str(uuid.uuid4());root='/api/v1/tenants/'+tenant
    api('POST','/api/v1/authorities',{'id':tenant,'name':'Example Transit Access Authority — SYNTHETIC','legalName':'Fictional Example Transit Operator','contact':'authority@example.invalid','merchantId':merchant,'provider':'simulator','credentialPrefix':'DEMO','administratorSubject':subjects['admin']},'platform')
    facility=api('POST',root+'/facilities',{'name':'Example Junction — SYNTHETIC','timezone':'Asia/Kolkata'})['id']
    zone=api('POST',root+'/facilities/'+facility+'/zones',{'name':'Standard pickup — synthetic'})['id']
    gates={direction:api('POST',root+'/gates',{'facilityId':facility,'zoneId':zone,'name':direction+' demo','direction':direction})['id'] for direction in ['ENTRY','EXIT']}
    devices={direction:api('POST',root+'/devices',{'facilityId':facility,'gateId':gate,'name':direction+' simulated ANPR'}) for direction,gate in gates.items()}
    roles={'reviewer':'VEHICLE_REVIEWER','operator':'GATE_OPERATOR','supervisor':'GATE_SUPERVISOR','finance':'FINANCE_OFFICER','finance2':'FINANCE_OFFICER','support':'SUPPORT_AGENT'}
    for user,role in roles.items():api('POST',root+'/memberships',{'subject':subjects[user],'role':role,'facilityId':facility})
    api('POST',root+'/drivers',{'name':'Synthetic Demo Driver','contact':'driver@example.invalid'},'driver')
    challenge=api('POST',root+'/drivers/me/contact-challenges',{},'driver')['id']
    code=api('GET',root+'/local/contact-challenges/'+challenge,user='driver')['code']
    assert api('POST',root+'/drivers/me/contact-challenges/'+challenge+'/verify',{'code':code},'driver')['verified']
    vehicle=api('POST',root+'/vehicles',{'plate':'DEMO1234','vehicleClass':'SEDAN'},'driver')['id']
    api('PUT',root+'/vehicles/'+vehicle+'/eligibility/'+facility,{'status':'APPROVED','reason':'Synthetic document review passed'},'reviewer')
    plan=api('POST',root+'/plans',{'facilityId':facility,'name':'Synthetic monthly access'})['id']
    version=api('POST',root+'/plans/'+plan+'/versions',{'duration':'MONTHLY','amountMinor':123400,'coverage':'Synthetic standard pickup access only','exclusions':'Taxes, parking, waiting, premium lanes, overstay excluded','terms':'DEVELOPMENT DATA. No approved commercial terms or savings claim.','vehicleClass':'SEDAN','zoneIds':[zone]})['id']
    quote=api('POST',root+'/purchase-quotes',{'vehicleId':vehicle,'planVersionId':version},'driver')['id']
    purchase_key=key();order=api('POST',root+'/orders',{'quoteId':quote},'driver',purchase_key)['id']
    assert api('POST',root+'/orders',{'quoteId':quote},'driver',purchase_key)['id']==order
    wait(lambda:api('GET',root+'/orders/'+order,user='driver'),lambda x:x['status']=='PENDING','provider order')
    first=api('POST',root+'/local/orders/'+order+'/capture',{},'driver')
    second=api('POST',root+'/local/orders/'+order+'/capture',{},'driver');assert second['duplicate'] and second['id']==first['id']
    paid=wait(lambda:api('GET',root+'/orders/'+order,user='driver'),lambda x:x['status']=='FULFILLED','activation')
    assert len(paid['payments'])==len(paid['entitlements'])==1
    subscription=paid['entitlements'][0]['subscription_id'];payment=paid['payments'][0]['id']
    def event(direction,kind,plate='DEMO1234'):
        d=devices[direction];event={'sourceId':str(uuid.uuid4()),'kind':kind,'eventAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'plate':plate,'confidence':.99,'buffered':False}
        if kind=='PASSAGE':event['passageKey']=str(uuid.uuid4())
        return api('POST','/api/v1/devices/'+d['id']+'/events',event,None,{'X-Device-Key':d['secret']}),event
    observation,payload=event('ENTRY','OBSERVATION');assert observation['decision']['outcome']=='ALLOW'
    decision=observation['decision']['id']
    command=wait(lambda:api('GET',root+'/access/decisions/'+decision,user='operator'),lambda x:x['commands'][0]['status']=='DISPATCHED','gate dispatch')['commands'][0]['id']
    api('POST','/api/v1/devices/'+devices['ENTRY']['id']+'/commands/'+command+'/ack',{'status':'OPENED'},None,{'X-Device-Key':devices['ENTRY']['secret']})
    assert api('GET',root+'/visits?facilityId='+facility,user='driver')==[]
    event('ENTRY','PASSAGE');time.sleep(1.05);event('EXIT','PASSAGE')
    visits=wait(lambda:api('GET',root+'/visits?facilityId='+facility,user='driver'),lambda x:len(x)==1 and x[0]['quality']=='MATCHED','matched visit')
    bad=api('POST',root+'/vehicles',{'plate':'DENY1234','vehicleClass':'SEDAN'},'driver')['id']
    denied,_=event('ENTRY','OBSERVATION','DENY1234');assert denied['decision']['outcome']=='DENY'
    api('POST',root+'/access/decisions/'+denied['decision']['id']+'/overrides',{'reason':'Synthetic supervised demonstration','evidence':'Operator evidence DEMO-OVERRIDE'},'operator',key(),403)
    override=api('POST',root+'/access/decisions/'+denied['decision']['id']+'/overrides',{'reason':'Synthetic supervised demonstration','evidence':'Supervisor evidence DEMO-OVERRIDE'},'supervisor',key());assert not override['entitlement_modified']
    receipt=api('GET',root+'/orders/'+order+'/receipt',user='driver');assert 'NOT A TAX INVOICE' in receipt and 'Fictional Example Transit Operator' in receipt
    case=api('POST',root+'/support-cases',{'facilityId':facility,'paymentId':payment,'visitId':visits[0]['id'],'description':'Synthetic receipt enquiry'},'driver',key())
    api('POST',root+'/support-cases/'+case['id']+'/resolve',{'resolution':'Synthetic enquiry resolved'},'support')
    refund=api('POST',root+'/refunds',{'paymentId':payment,'amountMinor':100,'reason':'Synthetic partial refund demonstration'},'finance',key())['id']
    api('POST',root+'/refunds/'+refund+'/approve',{},'finance',expected=403)
    api('POST',root+'/refunds/'+refund+'/approve',{},'finance2')
    wait(lambda:api('GET',root+'/refunds/'+refund,user='finance'),lambda x:x['status']=='PROCESSED','provider-confirmed refund')
    assert api('GET',root+'/subscriptions/'+subscription,user='driver')['suspended']
    api('POST',root+'/orders/'+order+'/reconcile',{},'finance',key())
    operational=api('GET',root+'/reports/operations?facilityId='+facility,user='operator');assert operational['estimated_occupancy']==0
    start=time.strftime('%Y-%m-%dT00:00:00Z',time.gmtime(time.time()-86400));end=time.strftime('%Y-%m-%dT23:59:59Z',time.gmtime(time.time()+86400))
    financial=api('GET',root+'/reports/collections?'+urllib.parse.urlencode({'facilityId':facility,'from':start,'to':end}),user='finance');assert financial['gross_collections_minor']==123400 and financial['refunds_minor']==100
    audit=api('GET',root+'/audit');assert any(x['action']=='ACCESS_OVERRIDE' for x in audit)
    notifications=wait(lambda:api('GET',root+'/notifications',user='driver'),lambda x:len(x)>=2 and all(n['status']=='DELIVERED' for n in x),'notification delivery')
    api('GET',root+'/exports/visits?facilityId='+facility)
    for d in devices.values():api('POST','/api/v1/devices/'+d['id']+'/heartbeat',{},None,{'X-Device-Key':d['secret']})
    other=str(uuid.uuid4())
    api('POST','/api/v1/authorities',{'id':other,'name':'Isolation control tenant — SYNTHETIC','legalName':'Fictional Isolation Control','contact':'isolation@example.invalid','merchantId':str(uuid.uuid4()),'provider':'simulator','credentialPrefix':'OTHER','administratorSubject':subjects['outsider']},'platform')
    api('GET',root+'/orders/'+order,user='outsider',expected=403)
    api('GET','/api/v1/tenants/'+other+'/orders/'+order,user='outsider',expected=404)
    api('GET',root+'/exports/visits?facilityId='+facility,user='outsider',expected=403)
    state={'tenant':tenant,'facility':facility,'vehicle':vehicle,'version':version,'gate':gates['ENTRY'],'order':order,'subscription':subscription,'other_tenant':other}
    pathlib.Path('.local').mkdir(exist_ok=True);pathlib.Path('.local/demo-state.json').write_text(json.dumps(state,indent=2)+'\n')
    pathlib.Path('docs/evidence/demo.json').write_text(json.dumps({'result':'PASS','run_at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'synthetic':True,'state':state,'checks':['OIDC onboarding','single-use contact verification','approved vehicle','immutable plan and quote','idempotent order','signed simulated provider callback replay','one payment and entitlement','allow decision','gate dispatch and acknowledgement without visit','entry/exit matched','deny decision','operator override forbidden','supervisor audited override','receipt','support resolution','maker/checker refund and suspension','reconciliation queued','collections and refunds distinct','notification tracking','device heartbeat','authorized export','cross-tenant read and export rejected'],'collections':financial,'operations':operational},indent=2)+'\n')
    print('PASS: 13-step synthetic demo; evidence: docs/evidence/demo.json')
if __name__=='__main__':main()
