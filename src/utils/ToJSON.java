package utils;

import java.util.Collection;

public class ToJSON {
  private ToJSON() {
  }

  public static String toJSON(Boolean b) {
    return b + "";
  }

  public static String toJSON(String s) {
    return toJSONNoExcape(s
        .replace("\"", "\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\r\n", "\\r\\n"));
  }

  public static String toJSONNoExcape(String s) {
    return "\"" + s + "\"";
  }

  public static String toJSON(Number n) {
    return n + "";
  }

  // from a collection of valid JSON strings
  // to a valid JSON array containing the elements of the collection
  public static String sequence(Collection<String> collection) {
    var toRet = "[";

    toRet += collection
        .stream()
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);

    toRet += "]";

    return toRet;

  }

}
