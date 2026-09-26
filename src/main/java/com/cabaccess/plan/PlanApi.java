package com.cabaccess.plan;

import com.cabaccess.identity.*;
import com.cabaccess.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenants/{t}")
public class PlanApi {
  private final PlanService service;private final TenantTx tx;private final Db db;
  public PlanApi(PlanService service,TenantTx tx,Db db){this.service=service;this.tx=tx;this.db=db;}
  public record PlanInput(@NotNull UUID facilityId,@NotBlank @Size(max=150) String name){}
  public record VersionInput(@NotNull CalendarPolicy.Duration duration,@Positive @Max(1000000000) long amountMinor,@NotBlank @Size(max=2000) String coverage,@NotBlank @Size(max=2000) String exclusions,@NotBlank @Size(max=4000) String terms,@NotBlank @Pattern(regexp="SEDAN|SUV|VAN") String vehicleClass,@NotEmpty @Size(max=50) List<@NotNull UUID> zoneIds){}
  public record QuoteInput(@NotNull UUID vehicleId,@NotNull UUID planVersionId,Instant futureStart){}
  @PostMapping("/plans") Mono<Map<String,Object>> create(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PlanInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.create(t,a,x));}
  @PostMapping("/plans/{id}/versions") Mono<Map<String,Object>> publish(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody VersionInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.publish(t,a,id,x));}
  @PostMapping("/plans/{id}/retire") Mono<Map<String,Object>> retire(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){var a=Actor.from(jwt);return tx.call(t,a,()->{var p=db.one("select * from plan where id=?",id);tx.permit(t,a,Db.id(p,"facility_id"),"AUTHORITY_ADMIN");db.update("update plan set retired=true where id=?",id);tx.audit(t,a,"PLAN_RETIRED",id,"Retired for new quotes");return Map.of("status","RETIRED");});}
  @GetMapping("/plans") Mono<List<Map<String,Object>>> list(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestParam UUID facilityId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,facilityId,"DRIVER","AUTHORITY_ADMIN","SUPPORT_AGENT");int n=Math.clamp(size,1,100);return db.list("select v.*,p.name from plan_version v join plan p on p.id=v.plan_id and p.tenant_id=v.tenant_id where v.facility_id=? and not p.retired order by v.published_at desc,v.id limit ? offset ?",facilityId,n,Math.max(page,0)*n);});}
  @PostMapping("/purchase-quotes") Mono<Map<String,Object>> quote(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody QuoteInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.quote(t,a,x));}
}
