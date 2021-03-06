package domain.comment;

import java.util.Date;

import utils.ToJSON;
// simple pojo
public class Comment {
  public Long timestamp;
  public String text;
  public String postUuid;
  public String author;

  public static Comment of(String text, String postUuid, String author) {

    var instance = new Comment();

    instance.text = text; // readonly
    instance.timestamp = new Date().getTime(); // readonly
    instance.postUuid = postUuid; // readonly
    instance.author = author; // readonly

    return instance;
  }

  public String toJSON() {

    return String.join("",
        "{",
        "\"text\":" + ToJSON.toJSON(this.text) + ",",
        "  \"timestamp\":" + this.timestamp + ",",
        "  \"postUuid\":" + "\"" + this.postUuid + "\"" + ",",
        "  \"author\":" + "\"" + this.author + "\"",
        "}");
  }
}
