package com.cabaccess.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(Failure.class) ResponseEntity<ProblemDetail> domain(Failure e) { return problem(e.status,e.code); }
  @ExceptionHandler({WebExchangeBindException.class,ServerWebInputException.class,IllegalArgumentException.class}) ResponseEntity<ProblemDetail> validation(Exception e) { return problem(400,"INVALID_REQUEST"); }
  @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<ProblemDetail> conflict(Exception e) { return problem(409,"INTEGRITY_CONFLICT"); }
  private ResponseEntity<ProblemDetail> problem(int status,String code) { var p=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),code);p.setProperty("code",code); return ResponseEntity.status(status).body(p); }
}
