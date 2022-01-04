package utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashPassword {
  private HashPassword() {
  }

  // hash a password using SHA-512
  public static String hash(String password) {
    try {

      var md = MessageDigest.getInstance("SHA-512");

      var bytes = md.digest(password.getBytes());

      var sb = new StringBuilder();
      for (int i = 0; i < bytes.length; i++) {
        sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16)
            .substring(1));
      }

      return sb.toString();

    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      // impossible, but...
      throw new RuntimeException(e.getMessage());
    }
  }
}
