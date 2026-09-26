package com.cabaccess.shared;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class Db {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public Db(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
  private Object[] args(Object[] values) { return Arrays.stream(values).map(v -> v instanceof Instant i ? Timestamp.from(i) : v).toArray(); }
  public int update(String sql, Object... values) { return jdbc.update(sql,args(values)); }
  public List<Map<String,Object>> list(String sql, Object... values) { return jdbc.queryForList(sql,args(values)); }
  public Map<String,Object> one(String sql, Object... values) {
    var result=list(sql,values); Failure.require(!result.isEmpty(),404,"NOT_FOUND"); return result.getFirst();
  }
  public Optional<Map<String,Object>> optional(String sql,Object... values) { return list(sql,values).stream().findFirst(); }
  public long count(String sql,Object... values) { return jdbc.queryForObject(sql,Long.class,args(values)); }
  public String json(Object value) { return json.writeValueAsString(value); }
  public Map<String,Object> parse(String value) { return json.readValue(value, new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}); }
  public static UUID id(Map<String,Object> row,String field) { return UUID.fromString(row.get(field).toString()); }
  public static String str(Map<String,Object> row,String field) { return Objects.toString(row.get(field),""); }
  public static long num(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
  public static Instant instant(Map<String,Object> row,String field) { Object x=row.get(field); return x instanceof Timestamp t?t.toInstant(): x instanceof java.time.OffsetDateTime o?o.toInstant():Instant.parse(x.toString()); }
}
