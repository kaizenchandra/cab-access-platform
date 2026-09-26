package com.cabaccess.access;

import com.cabaccess.identity.*;
import com.cabaccess.operations.Outbox;
import com.cabaccess.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
  private final Db db;private final TenantTx tx;private final Clock clock;private final Outbox outbox;
  public AccessService(Db db,TenantTx tx,Clock clock,Outbox outbox){this.db=db;this.tx=tx;this.clock=clock;this.outbox=outbox;}
  public Map<String,Object> decide(UUID t,UUID gate,UUID vehicle,BigDecimal confidence,String source,String evidence,boolean ambiguous){
    var g=db.one("select g.*,f.enabled,f.policy_version,z.enabled as zone_enabled from gate g join facility f on f.id=g.facility_id and f.tenant_id=g.tenant_id join zone z on z.id=g.zone_id and z.tenant_id=g.tenant_id where g.id=?",gate);Failure.require(Db.str(g,"direction").equals("ENTRY"),422,"ENTRY_GATE_REQUIRED");
    UUID attempt=UUID.randomUUID(),decision=UUID.randomUUID();Instant now=clock.instant();String reason="ELIGIBLE",outcome="ALLOW";UUID entitlement=null;
    if(ambiguous){reason="AMBIGUOUS_OBSERVATION";outcome="MANUAL_REVIEW";}
    else if(vehicle==null){reason="UNKNOWN_VEHICLE";outcome="MANUAL_REVIEW";}
    else if(confidence.compareTo(new BigDecimal("0.90"))<0){reason="LOW_CONFIDENCE";outcome="MANUAL_REVIEW";}
    else if(!Boolean.TRUE.equals(g.get("enabled"))||!Boolean.TRUE.equals(g.get("zone_enabled"))){reason="FACILITY_OR_ZONE_DISABLED";outcome="DENY";}
    else {
      var v=db.one("select * from vehicle where id=?",vehicle);
      var s=db.optional("select * from subscription where vehicle_id=? and facility_id=?",vehicle,Db.id(g,"facility_id"));
      if(Boolean.TRUE.equals(v.get("suspended"))){reason="VEHICLE_SUSPENDED";outcome="DENY";}
      else if(db.count("select count(*) from eligibility where vehicle_id=? and facility_id=? and status='APPROVED'",vehicle,Db.id(g,"facility_id"))!=1){reason="VEHICLE_NOT_APPROVED";outcome="DENY";}
      else if(s.isPresent()&&Boolean.TRUE.equals(s.get().get("suspended"))){reason="SUBSCRIPTION_SUSPENDED";outcome="DENY";}
      else {
        var period=s.isEmpty()?Optional.<Map<String,Object>>empty():db.optional("select * from entitlement_period where subscription_id=? and start_at<=? and end_at>? and not revoked order by start_at limit 1",Db.id(s.get(),"id"),now,now);
        if(period.isEmpty()){reason="NO_VALID_ENTITLEMENT";outcome="DENY";}
        else {entitlement=Db.id(period.get(),"id");var snapshot=db.parse(Db.str(period.get(),"snapshot"));var zones=(List<?>)snapshot.get("zone_ids");if(!zones.contains(Db.str(g,"zone_id"))){reason="ZONE_NOT_COVERED";outcome="DENY";}else if(!Db.str(v,"vehicle_class").equals(snapshot.get("vehicle_class"))){reason="VEHICLE_CLASS_RESTRICTED";outcome="DENY";}}
      }
    }
    db.update("insert into access_attempt(id,tenant_id,facility_id,gate_id,vehicle_id,source,evidence,confidence,created_at) values(?,?,?,?,?,?,?,?,?)",attempt,t,Db.id(g,"facility_id"),gate,vehicle,source,evidence,confidence,now);
    db.update("insert into access_decision(id,tenant_id,attempt_id,outcome,reason,entitlement_id,policy_version,decision_at,next_action) values(?,?,?,?,?,?,?,?,?)",decision,t,attempt,outcome,reason,entitlement,Db.str(g,"policy_version"),now,outcome.equals("ALLOW")?"AWAIT_GATE_ACK_AND_PASSAGE":"APPROVED_MANUAL_PROCESS");
    if(outcome.equals("ALLOW"))command(t,decision,gate);return decision(decision);
  }
  public Map<String,Object> decision(UUID id){var r=new LinkedHashMap<>(db.one("select * from access_decision where id=?",id));r.put("commands",db.list("select * from gate_command where decision_id=?",id));return r;}
  private void command(UUID t,UUID decision,UUID gate){UUID id=UUID.randomUUID();db.update("insert into gate_command(id,tenant_id,decision_id,gate_id,status,expires_at) values(?,?,?,?,'PENDING',?) on conflict(tenant_id,decision_id) do nothing",id,t,decision,gate,clock.instant().plusSeconds(10));outbox.enqueue(t,"GATE",id);}
  public Map<String,Object> override(UUID t,Actor a,UUID id,String reason,String evidence){var d=db.one("select d.*,a.facility_id,a.gate_id from access_decision d join access_attempt a on a.id=d.attempt_id and a.tenant_id=d.tenant_id where d.id=?",id);tx.permit(t,a,Db.id(d,"facility_id"),"GATE_SUPERVISOR");Failure.require(reason!=null&&!reason.isBlank()&&evidence!=null&&!evidence.isBlank(),400,"OVERRIDE_REASON_EVIDENCE_REQUIRED");Failure.require(!Db.str(d,"outcome").equals("ALLOW"),409,"ALREADY_ALLOWED");Failure.require(Db.instant(d,"decision_at").plusSeconds(120).isAfter(clock.instant()),409,"STALE_DECISION_REASSESS_REQUIRED");UUID override=UUID.randomUUID();db.update("insert into access_override(id,tenant_id,decision_id,reason,evidence,actor,created_at) values(?,?,?,?,?,?,?)",override,t,id,reason,evidence,a.subject(),clock.instant());command(t,id,Db.id(d,"gate_id"));tx.audit(t,a,"ACCESS_OVERRIDE",id,reason);return Map.of("id",override,"original_decision",decision(id),"entitlement_modified",false);}
}
