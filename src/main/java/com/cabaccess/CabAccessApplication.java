package com.cabaccess;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CabAccessApplication {
  public static void main(String[] args) { SpringApplication.run(CabAccessApplication.class, args); }
  @Bean Clock clock() { return Clock.systemUTC(); }
}
