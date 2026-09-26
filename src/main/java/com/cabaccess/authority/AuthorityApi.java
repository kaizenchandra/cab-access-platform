package com.cabaccess.authority;

import com.cabaccess.identity.*;
import com.cabaccess.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1")
public class AuthorityApi {
  private final AuthorityService service;private final TenantTx tx;private final Db db;private final Clock clock;
  public AuthorityApi(AuthorityService service,TenantTx tx,Db db,Clock clock){this.service=service;this.tx=tx;this.db=db;this.clock=clock;}
  public record AuthorityInput(@NotNull UUID id,@NotBlank @Size(max=160) String name,@NotBlank @Size(max=200) String legalName,@NotBlank @Email String contact,@NotNull UUID merchantId,@Pattern(regexp="simulator|razorpay") @NotNull String provider,@Pattern(regexp="[A-Z][A-Z0-9_]{2,40}") @NotNull String credentialPrefix,@NotBlank String administratorSubject){}
  public record FacilityInput(@NotBlank @Size(max=160) String name,@NotBlank String timezone){}
  public record NameInput(@NotBlank @Size(max=160) String name){}
  public record GateInput(@NotNull UUID facilityId,@NotNull UUID zoneId,@NotBlank String name,@Pattern(regexp="ENTRY|EXIT") @NotNull String direction){}
  public record DeviceInput(@NotNull UUID facilityId,@NotNull UUID gateId,@NotBlank String name){}
  public record MembershipInput(@NotBlank String subject,@Pattern(regexp="AUTHORITY_ADMIN|VEHICLE_REVIEWER|GATE_OPERATOR|GATE_SUPERVISOR|FINANCE_OFFICER|DRIVER|SUPPORT_AGENT") @NotNull String role,UUID facilityId){}
  public record GrantInput(@NotBlank String subject,@NotNull Instant expiresAt,@NotBlank @Size(max=500) String reason){}
  @PostMapping("/authorities") Mono<Map<String,Object>> onboard(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AuthorityInput x){return Mono.fromCallable(()->service.onboard(Actor.from(jwt),x)).subscribeOn(Schedulers.boundedElastic());}
  @GetMapping("/tenants/{t}/authority") Mono<Map<String,Object>> authority(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,null,"AUTHORITY_ADMIN");return db.one("select * from authority where tenant_id=?",t);});}
  @PostMapping("/tenants/{t}/memberships") Mono<Map<String,Object>> membership(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MembershipInput x){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,null,"AUTHORITY_ADMIN");service.membership(t,x.subject(),x.role(),x.facilityId());tx.audit(t,a,"MEMBERSHIP_CHANGED",t,x.subject()+":"+x.role());return Map.of("status","ACTIVE");});}
  @PostMapping("/tenants/{t}/support-grants") Mono<Map<String,Object>> grant(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody GrantInput x){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,null,"AUTHORITY_ADMIN");Failure.require(x.expiresAt().isAfter(clock.instant()) && !x.expiresAt().isAfter(clock.instant().plus(Duration.ofHours(8))),400,"GRANT_MAXIMUM_8_HOURS");UUID id=UUID.randomUUID();db.update("insert into support_grant(id,tenant_id,subject,expires_at,granted_by,reason) values(?,?,?,?,?,?)",id,t,x.subject(),x.expiresAt(),a.subject(),x.reason());tx.audit(t,a,"SUPPORT_GRANTED",id,x.reason());return Map.of("id",id);});}
  @DeleteMapping("/tenants/{t}/support-grants/{id}") Mono<Map<String,Object>> revoke(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){var a=Actor.from(jwt);return tx.call(t,a,()->{tx.permit(t,a,null,"AUTHORITY_ADMIN");Failure.require(db.update("update support_grant set revoked_at=? where id=?",clock.instant(),id)==1,404,"NOT_FOUND");tx.audit(t,a,"SUPPORT_REVOKED",id,"Revoked");return Map.of("status","REVOKED");});}
  @PostMapping("/tenants/{t}/facilities") Mono<Map<String,Object>> facility(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody FacilityInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.facility(t,a,x));}
  @GetMapping("/tenants/{t}/facilities") Mono<List<Map<String,Object>>> facilities(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){var a=Actor.from(jwt);return tx.call(t,a,()->{int n=Math.clamp(size,1,100);var m=tx.member(t,a);String scope=Db.str(m,"facility_id");return db.list("select * from facility where tenant_id=? and (?='' or id::text=?) order by id limit ? offset ?",t,scope,scope,n,Math.max(page,0)*n);});}
  @PostMapping("/tenants/{t}/facilities/{f}/zones") Mono<Map<String,Object>> zone(@PathVariable UUID t,@PathVariable UUID f,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody NameInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.zone(t,a,f,x.name()));}
  @PostMapping("/tenants/{t}/gates") Mono<Map<String,Object>> gate(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody GateInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.gate(t,a,x));}
  @PostMapping("/tenants/{t}/devices") Mono<Map<String,Object>> device(@PathVariable UUID t,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody DeviceInput x){var a=Actor.from(jwt);return tx.call(t,a,()->service.device(t,a,x));}
  @PatchMapping("/tenants/{t}/devices/{id}/disable") Mono<Map<String,Object>> disable(@PathVariable UUID t,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){var a=Actor.from(jwt);return tx.call(t,a,()->{var d=db.one("select * from device where id=?",id);tx.permit(t,a,Db.id(d,"facility_id"),"AUTHORITY_ADMIN");db.update("update device set active=false where id=?",id);tx.audit(t,a,"DEVICE_DISABLED",id,"Disabled");return Map.of("status","DISABLED");});}
}
