package domain.post;

import utils.ToJSON;

public class AuthorPostUuid {

  public String author;
  public String postUuid;

  public AuthorPostUuid() {
  }

  private AuthorPostUuid(String author, String postUuid) {
    this.author = author;
    this.postUuid = postUuid;
  }

  public static AuthorPostUuid of(String author, String postUuid) {
    return new AuthorPostUuid(author != null ? author : "", postUuid != null ? postUuid : "");
  }

  public String ToJSON() {
    return "{\"author\":" + ToJSON.toJSON(author) + ",\"postUuid\":" + ToJSON.toJSON(postUuid) + "}";
  }
}
