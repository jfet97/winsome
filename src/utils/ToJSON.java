package utils;

public class ToJSON {
  private ToJSON() {
  }

  public static String toJSON(Boolean b) {
    return b + "";
  }

  public static String toJSON(String s) {
    var formattedString = s
        .replace("\"", "\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\r\n", "\\r\\n");

    return "\"" + formattedString + "\"";
  }

  public static String toJSON(Number n) {
    return n + "";
  }

}
