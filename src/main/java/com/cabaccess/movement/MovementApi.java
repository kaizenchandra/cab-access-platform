package com.cabaccess.movement;

import com.cabaccess.identity.*;
import com.cabaccess.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1")
public class MovementApi {
  private final MovementService service;private final TenantTx tx;private final Db db;private final Clock clock;private final Idempotency idem;
  public MovementApi(MovementService service,TenantTx tx,Db db,Clock clock,Idempotency idem){this.service=service;this.tx=tx;this.db=db;this.clock=clock;this.idem=idem;}
  public record EventInput(@NotBlank @Size(max=150) String sourceId,@NotNull @Pattern(regexp="OBSERVATION|PASSAGE") String kind,@NotNull Instant eventAt,@NotBlank @Pattern(regexp="[A-Za-z0-9 -]{4,20}") String plate,@NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,boolean buffered,@Size(max=150) String passageKey){}
  public record AckInput(@NotNull @Pattern(regexp="OPENED|FAILED") String status){}
  public record CorrectionInput(@NotNull Instant exitAt,@NotBlank @Size(max=500) String reason){}
  public record ExceptionReview(@NotBlank @Size(max=500) String reason){}
  @PostMapping("/devices/{id}/events") Mono<Map<String,Object>> event(@PathVariable UUID id,@RequestHeader("X-Device-Key") String key,@Valid @RequestBody EventInput x){return Mono.fromCallable(()->{UUID t=service.authenticate(id,key);return tx.system(t,()->service.ingest(t,id,x));}).subscribeOn(Schedulers.boundedElastic());}
  @PostMapping("/devices/{id}/heartbeat") Mono<Map<String,Object>> heartbeat(@PathVariable UUID id,@RequestHeader("X-Device-Key") String key){return Mono.fromCallable(()->{UUID t=service.authenticate(id,key);return tx.system(t,()->{service.device(id);db.update("update device set last_heartbeat=? where id=?",clock.instant(),id);return Map.<String,Object>of("received_at",clock.instant());});}).subscribeOn(Schedulers.boundedElastic());}
  @PostMapping("/devices/{id}/commands/{command}/ack") Mono<Map<String,Object>> ack(@PathVariable UUID id,@PathVariable UUID command,@RequestHeader("X-Device-Key") String key,@Valid @RequestBody AckInput x){return Mono.fromCallable(()->{UUID t=service.authenticate(id,key);return tx.system(t,()->service.acknowledge(id,command,x.status()));}).subscribeOn(Schedulers.boundedElastic());}
  @GetMapping("/tenants/{t}/visits") Mono<List<Map<String,Object>>> visits(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestParam UUID facilityId,@RequestParam(required=false) UUID vehicleId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){var a=Actor.from(jwt);return tx.call(t,a,()->{boolean own=tx.driver(t,a);if(!own)tx.permit(t,a,facilityId,"AUTHORITY_ADMIN","GATE_OPERATOR","GATE_SUPERVISOR","SUPPORT_AGENT");int n=Math.clamp(size,1,100);return db.list("select v.*,coalesce((select c.corrected_exit_at from visit_correction c where c.visit_id=v.id order by c.created_at desc,c.id desc limit 1),v.exit_at) as effective_exit_at from visit v join vehicle vh on vh.id=v.vehicle_id and vh.tenant_id=v.tenant_id join driver_profile d on d.id=vh.driver_id and d.tenant_id=vh.tenant_id where v.facility_id=? and (?::uuid is null or v.vehicle_id=?) and (not ? or d.subject=?) order by v.entry_at desc,v.id limit ? offset ?",facilityId,vehicleId,vehicleId,own,a.subject(),n,Math.max(page,0)*n);});}
  @PostMapping("/tenants/{t}/visits/{id}/corrections") Mono<Map<String,Object>> correction(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CorrectionInput x){var a=Actor.from(jwt);return tx.call(t,a,()->{var v=db.one("select * from visit where id=?",id);tx.permit(t,a,Db.id(v,"facility_id"),"GATE_SUPERVISOR");Failure.require(x.exitAt().isAfter(Db.instant(v,"entry_at"))&&!x.exitAt().isAfter(clock.instant()),400,"INVALID_CORRECTED_EXIT");return idem.execute(t,a.subject(),"VISIT_CORRECTION",key,Map.of("visit",id,"input",x),()->{UUID correction=UUID.randomUUID();db.update("insert into visit_correction(id,tenant_id,visit_id,corrected_exit_at,actor,reason,created_at) values(?,?,?,?,?,?,?)",correction,t,id,x.exitAt(),a.subject(),x.reason(),clock.instant());tx.audit(t,a,"VISIT_CORRECTED",id,x.reason());return Map.of("id",correction);});});}
  @GetMapping("/tenants/{t}/movement-exceptions") Mono<List<Map<String,Object>>> exceptions(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestParam UUID facilityId,@RequestParam(defaultValue="0") int page){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,facilityId,"GATE_SUPERVISOR","AUTHORITY_ADMIN","SUPPORT_AGENT");return db.list("select * from movement_exception where facility_id=? order by created_at desc limit 100 offset ?",facilityId,Math.max(page,0)*100);});}
  @PostMapping("/tenants/{t}/movement-exceptions/{id}/review") Mono<Map<String,Object>> review(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ExceptionReview x){var a=Actor.from(jwt);return tx.call(t,a,()->{var e=db.one("select * from movement_exception where id=?",id);tx.permit(t,a,Db.id(e,"facility_id"),"GATE_SUPERVISOR");db.update("update movement_exception set status='REVIEWED' where id=?",id);tx.audit(t,a,"MOVEMENT_EXCEPTION_REVIEWED",id,x.reason());return Map.of("status","REVIEWED");});}
}
