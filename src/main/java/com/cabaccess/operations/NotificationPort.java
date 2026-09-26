package com.cabaccess.operations;

import java.util.*;

public interface NotificationPort {
  record Message(UUID id,String recipient,String kind,Map<String,Object> details){}
  String deliver(Message message);
}
