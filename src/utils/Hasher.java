package utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hasher {
  private Hasher() {
  }

  // hash a string using SHA-512
  public static String hash(String string) {
    try {

      var md = MessageDigest.getInstance("SHA-512");

      var bytes = md.digest(string.getBytes());

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
