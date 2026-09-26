package com.cabaccess.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretBox {
  private final String configured;private final boolean local;
  public SecretBox(@Value("${cab.contact-encryption-key:}") String configured,@Value("${cab.local-delivery}") boolean local){this.configured=configured;this.local=local;}
  private byte[] key(){if(local&&configured.isBlank())return HexFormat.of().parseHex(Crypto.hash("synthetic-local-contact-key"));Failure.require(!configured.isBlank(),503,"CONTACT_ENCRYPTION_KEY_REQUIRED");byte[] key=Base64.getDecoder().decode(configured);Failure.require(key.length==32,503,"CONTACT_ENCRYPTION_KEY_INVALID");return key;}
  public String encrypt(String plaintext){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));byte[] result=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(result);}catch(GeneralSecurityException e){throw new IllegalStateException("Contact encryption failed",e);}}
  public String decrypt(String ciphertext){try{byte[] data=Base64.getDecoder().decode(ciphertext);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));return new String(cipher.doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8);}catch(GeneralSecurityException e){throw new IllegalStateException("Contact decryption failed",e);}}
}
