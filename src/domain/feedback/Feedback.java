package domain.feedback;

public class Feedback {

  public String res;
  public Boolean isOk;

  private static Feedback of(String res, Boolean isOk) {
    var instance = new Feedback();

    instance.res = res != null ? res : "";
    instance.isOk = isOk != null ? isOk : false;

    return instance;
  }

  public static Feedback error(String res) {
    return of(res, false);
  }

  public static Feedback right(String res) {
    return of(res, true);
  }

  public String toJSON() {

    return "{\"res\":\"" + this.res + "\", \"ok\": " + this.isOk + "}";

  }

}
