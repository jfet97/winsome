package domain.feedback;

// used to wrap the response returned to the client
public class Feedback {

  public String jsonRes;
  public Boolean isOk;

  private static Feedback of(String jsonRes, Boolean isOk) {
    var instance = new Feedback();

    instance.jsonRes = jsonRes != null ? jsonRes : "";
    instance.isOk = isOk != null ? isOk : false;

    return instance;
  }

  // negative response
  public static Feedback error(String jsonRes) {
    return of(jsonRes, false);
  }

  // positive response
  public static Feedback right(String jsonRes) {
    return of(jsonRes, true);
  }

  public String toJSON() {

    return "{\"res\":" + this.jsonRes + ", \"ok\": " + this.isOk + "}";

  }

  public String toString() {
    return this.toJSON();
  }

}
