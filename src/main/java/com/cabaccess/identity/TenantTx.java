package com.cabaccess.identity;

import com.cabaccess.shared.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class TenantTx {
  private final TransactionTemplate tx;
  private final Db db;
  private final Clock clock;
  public TenantTx(PlatformTransactionManager manager,Db db,Clock clock) { tx=new TransactionTemplate(manager);tx.setTimeout(15);this.db=db;this.clock=clock; }
  public <T> T system(UUID tenant,Supplier<T> action) { return tx.execute(s -> {db.one("select set_config('app.tenant',?,true)",tenant.toString());return action.get();}); }
  public <T> Mono<T> call(UUID tenant,Actor actor,Supplier<T> action) { return Mono.fromCallable(() -> system(tenant,() -> {member(tenant,actor);return action.get();})).subscribeOn(Schedulers.boundedElastic()); }
  public Map<String,Object> member(UUID tenant,Actor actor) {
    var membership=db.optional("select * from membership where tenant_id=? and subject=? and active",tenant,actor.subject());
    if (membership.isPresent()) return membership.get();
    if(actor.platformAdmin() && db.count("select count(*) from support_grant where tenant_id=? and subject=? and expires_at>? and revoked_at is null",tenant,actor.subject(),clock.instant())>0) {
      audit(tenant,actor,"SUPPORT_ACCESS",tenant,"Time-bound support grant");return Map.of("role","SUPPORT_AGENT","facility_id","");
    }
    throw new Failure(403,"TENANT_MEMBERSHIP_REQUIRED");
  }
  public void permit(UUID tenant,Actor actor,UUID facility,String... roles) {
    var m=member(tenant,actor);String role=Db.str(m,"role");
    Failure.require(Arrays.asList(roles).contains(role),403,"ROLE_FORBIDDEN");
    String scope=Db.str(m,"facility_id"); Failure.require(scope.isEmpty() || facility!=null && scope.equals(facility.toString()),403,"FACILITY_FORBIDDEN");
  }
  public boolean driver(UUID tenant,Actor actor) { return "DRIVER".equals(Db.str(member(tenant,actor),"role")); }
  public void owner(UUID tenant,Actor actor,String subject,UUID facility,String... staffRoles) { if(driver(tenant,actor)) Failure.require(actor.subject().equals(subject),403,"OWNER_REQUIRED"); else permit(tenant,actor,facility,staffRoles); }
  public void audit(UUID tenant,Actor actor,String action,UUID resource,String reason) { db.update("insert into audit_entry(id,tenant_id,actor,action,resource_id,reason,created_at) values(?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,actor.subject(),action,resource,reason,clock.instant()); }
}
