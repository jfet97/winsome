package domain.post;

import utils.ToJSON;

public class AuthorPostUuidPair {

  public String author;
  public String postUuid;

  public AuthorPostUuidPair() {
  }

  private AuthorPostUuidPair(String author, String postUuid) {
    this.author = author;
    this.postUuid = postUuid;
  }

  public static AuthorPostUuidPair of(String author, String postUuid) {
    return new AuthorPostUuidPair(author != null ? author : "", postUuid != null ? postUuid : "");
  }

  public String ToJSON() {
    return "{\"author\":" + ToJSON.toJSON(author) + ",\"postUuid\":" + ToJSON.toJSON(postUuid) + "}";
  }
}
