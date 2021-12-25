package utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class HashPassword {
  private HashPassword() {
  }

  public static String hash(String password) {
    try {
      var random = new SecureRandom();
      var salt = new byte[16];
      random.nextBytes(salt);

      var md = MessageDigest.getInstance("SHA-512");
      md.update(salt);

      return md.digest(password.getBytes(StandardCharsets.UTF_8)).toString();
    } catch (NoSuchAlgorithmException e) {
      // impossible, but...
      throw new RuntimeException(e.getMessage());
    }
  }
}
