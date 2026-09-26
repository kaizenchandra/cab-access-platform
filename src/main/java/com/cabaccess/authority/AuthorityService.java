package com.cabaccess.authority;

import com.cabaccess.identity.*;
import com.cabaccess.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AuthorityService {
  private final Db db; private final TenantTx tx; private final Clock clock;
  public AuthorityService(Db db,TenantTx tx,Clock clock){this.db=db;this.tx=tx;this.clock=clock;}
  public Map<String,Object> onboard(Actor actor,AuthorityApi.AuthorityInput input){
    Failure.require(actor.platformAdmin(),403,"PLATFORM_ADMIN_REQUIRED");
    return tx.system(input.id(),()->{
      db.update("insert into tenant_directory(id) values(?) on conflict do nothing",input.id());
      Failure.require(db.count("select count(*) from authority where tenant_id=?",input.id())==0,409,"AUTHORITY_EXISTS");
      db.update("insert into merchant_route(id,tenant_id,provider,credential_prefix) values(?,?,?,?)",input.merchantId(),input.id(),input.provider(),input.credentialPrefix());
      db.update("insert into authority(id,tenant_id,name,legal_name,contact,merchant_id,created_at) values(?,?,?,?,?,?,?)",input.id(),input.id(),input.name(),input.legalName(),input.contact(),input.merchantId(),clock.instant());
      membership(input.id(),input.administratorSubject(),"AUTHORITY_ADMIN",null);
      tx.audit(input.id(),actor,"AUTHORITY_CREATED",input.id(),"Onboarding");return db.one("select * from authority where tenant_id=?",input.id());
    });
  }
  public void membership(UUID tenant,String subject,String role,UUID facility){
    db.update("insert into identity_account(subject) values(?) on conflict do nothing",subject);
    db.update("insert into membership(id,tenant_id,subject,role,facility_id) values(?,?,?,?,?) on conflict(tenant_id,subject) do update set role=excluded.role,facility_id=excluded.facility_id,active=true",UUID.randomUUID(),tenant,subject,role,facility);
  }
  public Map<String,Object> facility(UUID t,Actor a,AuthorityApi.FacilityInput x){tx.permit(t,a,null,"AUTHORITY_ADMIN");ZoneId.of(x.timezone());UUID id=UUID.randomUUID();db.update("insert into facility(id,tenant_id,name,timezone) values(?,?,?,?)",id,t,x.name(),x.timezone());tx.audit(t,a,"FACILITY_CREATED",id,x.name());return db.one("select * from facility where id=?",id);}
  public Map<String,Object> zone(UUID t,Actor a,UUID f,String name){tx.permit(t,a,f,"AUTHORITY_ADMIN");UUID id=UUID.randomUUID();db.update("insert into zone(id,tenant_id,facility_id,name) values(?,?,?,?)",id,t,f,name);return db.one("select * from zone where id=?",id);}
  public Map<String,Object> gate(UUID t,Actor a,AuthorityApi.GateInput x){tx.permit(t,a,x.facilityId(),"AUTHORITY_ADMIN");UUID id=UUID.randomUUID();db.update("insert into gate(id,tenant_id,facility_id,zone_id,name,direction) values(?,?,?,?,?,?)",id,t,x.facilityId(),x.zoneId(),x.name(),x.direction());return db.one("select * from gate where id=?",id);}
  public Map<String,Object> device(UUID t,Actor a,AuthorityApi.DeviceInput x){
    tx.permit(t,a,x.facilityId(),"AUTHORITY_ADMIN");UUID id=UUID.randomUUID();String secret=UUID.randomUUID()+"-"+UUID.randomUUID();
    db.update("insert into device_route(id,tenant_id,secret_hash) values(?,?,?)",id,t,Crypto.hash(secret));
    db.update("insert into device(id,tenant_id,facility_id,gate_id,name) values(?,?,?,?,?)",id,t,x.facilityId(),x.gateId(),x.name());tx.audit(t,a,"DEVICE_REGISTERED",id,"Credential issued once");return Map.of("id",id,"secret",secret,"facility_id",x.facilityId(),"gate_id",x.gateId());
  }
}
