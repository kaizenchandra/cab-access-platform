package com.cabaccess.operations;

import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {
  private final Db db;private final TenantTx tx;private final Clock clock;private final NotificationPort adapter;private final SecretBox secrets;
  public NotificationWorker(Db db,TenantTx tx,Clock clock,NotificationPort adapter,SecretBox secrets){this.db=db;this.tx=tx;this.clock=clock;this.adapter=adapter;this.secrets=secrets;}
  public void deliver(UUID t,UUID id){var n=tx.system(t,()->db.one("select n.*,d.contact from notification n join driver_profile d on d.subject=n.subject and d.tenant_id=n.tenant_id where n.id=?",id));if(Db.str(n,"status").equals("DELIVERED"))return;String receipt=adapter.deliver(new NotificationPort.Message(id,Db.str(n,"contact"),Db.str(n,"kind"),Map.of("resource_id",n.get("resource_id"))));tx.system(t,()->{db.update("update notification set status='DELIVERED',delivery_reference=?,delivered_at=? where id=?",receipt,clock.instant(),id);return true;});}
  public void contact(UUID t,UUID id){var row=tx.system(t,()->db.optional("select l.code,c.expires_at,c.used_at,d.contact from contact_challenge c join driver_profile d on d.id=c.driver_id and d.tenant_id=c.tenant_id join local_delivery l on l.challenge_id=c.id and l.tenant_id=c.tenant_id where c.id=?",id));if(row.isEmpty()||row.get().get("used_at")!=null||!Db.instant(row.get(),"expires_at").isAfter(clock.instant()))return;adapter.deliver(new NotificationPort.Message(id,Db.str(row.get(),"contact"),"CONTACT_VERIFICATION",Map.of("code",secrets.decrypt(Db.str(row.get(),"code")),"expires_at",Db.instant(row.get(),"expires_at"))));}
}
