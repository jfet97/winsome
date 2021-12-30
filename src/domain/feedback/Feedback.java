package domain.feedback;

public class Feedback {

  public String message;
  public Boolean isOk;

  private static Feedback of(String message, Boolean isOk) {
    var instance = new Feedback();

    instance.message = message != null ? message : "";
    instance.isOk = isOk != null ? isOk : false;

    return instance;
  }

  public static Feedback error(String message) {
    return of(message, false);
  }

  public static Feedback right(String message) {
    return of(message, true);
  }

  public String toJSON() {

    return "{\"message\":\"" + this.message + "\", \"ok\": " + this.isOk + "}";

  }

}
