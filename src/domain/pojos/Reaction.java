package domain.pojos;

import java.util.Date;

public class Reaction {
  public Long date;
  public Boolean isUpvote; // true for upvotes, false for downvotes
  public String postUuid;
  public String username;

  public static Reaction of(Boolean isUpvote, String postUuid, String username) {

    var instance = new Reaction();

    instance.isUpvote = isUpvote; // readonly
    instance.date = new Date().getTime(); // readonly
    instance.postUuid = postUuid; // readonly
    instance.username = username; // readonly

    return instance;
  }

  @Override
  public String toString() {

    return String.join("\n",
        "{",
        "  \"isUpvote\": " + this.isUpvote + ",",
        "  \"date\": " + this.date + ",",
        "  \"postUuid\": " + "\"" + this.postUuid + "\"" + ",",
        "  \"username\": " + "\"" + this.username + "\"",
        "}");
  }
}
