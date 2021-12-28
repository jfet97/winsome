package domain.wallet;

import java.util.Date;

public class WalletTransaction {
  public Double gain;
  public Long timestamp;

  public static WalletTransaction of(Double gain) {
    var instance = new WalletTransaction();

    instance.timestamp = new Date().getTime(); // readonly
    instance.gain = gain;

    return instance;
  }

  public String toJSON() {

    return String.join("",
        "{",
        "\"timestamp\":" + this.timestamp + ",",
        "\"gain\":" + this.gain,
        "}");
  }
}
