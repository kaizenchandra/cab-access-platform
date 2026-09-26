package com.cabaccess;

import org.flywaydb.core.Flyway;

/** One-off migration entry point; no web server or runtime workers. */
public final class MigrationMain {
  private MigrationMain(){}
  public static void main(String[] args){Flyway.configure().dataSource(required("DATABASE_URL"),required("MIGRATION_USER"),required("MIGRATION_PASSWORD")).locations("classpath:db/migration").load().migrate();}
  private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Required environment variable: "+name);return value;}
}
