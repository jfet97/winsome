package domain.error;

public class Error {

  public String message;

  public static Error of(String message) {
    var instance = new Error();

    instance.message = message != null ? message : "";

    return instance;
  }

  public String toJSON() {

    return "{\"message\":\"" + this.message + "\"}";

  }

}
