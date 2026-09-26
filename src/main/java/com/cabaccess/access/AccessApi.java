package com.cabaccess.access;

import com.cabaccess.identity.*;
import com.cabaccess.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenants/{t}")
public class AccessApi {
  private final AccessService service;private final TenantTx tx;private final Db db;private final Idempotency idem;
  public AccessApi(AccessService service,TenantTx tx,Db db,Idempotency idem){this.service=service;this.tx=tx;this.db=db;this.idem=idem;}
  public record FallbackInput(@NotNull UUID gateId,@NotNull UUID vehicleId,@NotBlank @Size(min=8,max=1000) String inspectedEvidence){}
  public record OverrideInput(@NotBlank @Size(max=500) String reason,@NotBlank @Size(max=1000) String evidence){}
  @GetMapping("/operator/plate-lookup") Mono<List<Map<String,Object>>> lookup(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestParam UUID facilityId,@RequestParam String plate){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,facilityId,"GATE_OPERATOR","GATE_SUPERVISOR");tx.audit(t,a,"PLATE_LOOKUP",facilityId,"Operator lookup; no admission evidence");return db.list("select v.id,v.plate,v.vehicle_class,e.status as eligibility from vehicle v left join eligibility e on e.vehicle_id=v.id and e.tenant_id=v.tenant_id and e.facility_id=? where v.plate=?",facilityId,plate.replace(" ","").toUpperCase(Locale.ROOT));});}
  @PostMapping("/access/fallback-decisions") Mono<Map<String,Object>> fallback(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FallbackInput x){var a=Actor.from(jwt);return tx.call(t,a,()->{var gate=db.one("select * from gate where id=?",x.gateId());tx.permit(t,a,Db.id(gate,"facility_id"),"GATE_OPERATOR","GATE_SUPERVISOR");return idem.execute(t,a.subject(),"FALLBACK",key,x,()->{tx.audit(t,a,"FALLBACK_INSPECTION",x.vehicleId(),x.inspectedEvidence());return service.decide(t,x.gateId(),x.vehicleId(),BigDecimal.ONE,"OPERATOR",x.inspectedEvidence(),false);});});}
  @GetMapping("/access/decisions/{id}") Mono<Map<String,Object>> decision(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){var a=Actor.from(jwt);return tx.call(t,a,()->{var attempt=db.one("select a.* from access_attempt a join access_decision d on d.attempt_id=a.id and d.tenant_id=a.tenant_id where d.id=?",id);tx.permit(t,a,Db.id(attempt,"facility_id"),"GATE_OPERATOR","GATE_SUPERVISOR","AUTHORITY_ADMIN","SUPPORT_AGENT");return service.decision(id);});}
  @PostMapping("/access/decisions/{id}/overrides") Mono<Map<String,Object>> override(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OverrideInput x){var a=Actor.from(jwt);return tx.call(t,a,()->idem.execute(t,a.subject(),"OVERRIDE",key,Map.of("decision",id,"input",x),()->service.override(t,a,id,x.reason(),x.evidence())));}
}
