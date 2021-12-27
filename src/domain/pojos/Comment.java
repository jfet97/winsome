package domain.pojos;

import java.util.Date;

public class Comment {
  public Long date;
  public String text;
  public String postUuid;
  public String username;

  public static Comment of(String text, String postUuid, String username) {

    var instance = new Comment();

    instance.text = text; // readonly
    instance.date = new Date().getTime(); // readonly
    instance.postUuid = postUuid; // readonly
    instance.username = username; // readonly

    return instance;
  }

  @Override
  public String toString() {

    return String.join("\n",
        "{",
        "  \"text\": " + "\"" + this.text + "\"" + ",",
        "  \"date\": " + this.date + ",",
        "  \"postUuid\": " + "\"" + this.postUuid + "\"" + ",",
        "  \"username\": " + "\"" + this.username + "\"",
        "}");
  }
}
