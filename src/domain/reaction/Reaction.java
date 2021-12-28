package domain.reaction;

import java.util.Date;

public class Reaction {
  public Long timestamp;
  public Boolean isUpvote; // true for upvotes, false for downvotes
  public String postUuid;
  public String username;

  public static Reaction of(Boolean isUpvote, String postUuid, String username) {

    var instance = new Reaction();

    instance.isUpvote = isUpvote; // readonly
    instance.timestamp = new Date().getTime(); // readonly
    instance.postUuid = postUuid; // readonly
    instance.username = username; // readonly

    return instance;
  }

  public String toJSON() {

    return String.join("",
        "{",
        "\"isUpvote\":" + this.isUpvote + ",",
        "\"timestamp\":" + this.timestamp + ",",
        "\"postUuid\":" + "\"" + this.postUuid + "\"" + ",",
        "\"username\":" + "\"" + this.username + "\"",
        "}");
  }
}
