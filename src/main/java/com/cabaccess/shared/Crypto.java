package com.cabaccess.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
  private Crypto() {}
  public static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
  public static String hmac(byte[] value,String secret) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value)); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
  public static boolean same(String a,String b) { return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
}
