package domain.user;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import utils.ToJSON;

public class UserTags {
  public String username;
  public List<String> tags;

  public UserTags() {
  }

  private UserTags(String username, List<String> tags) {
    this.username = username;
    this.tags = tags;
  }

  public static UserTags of(String username, List<String> tags) {
    return new UserTags(username != null ? username : "", tags != null ? tags : new LinkedList<>());
  }

  public String ToJSON() {
    return "{\"username\":" + ToJSON.toJSON(username) +
        ",\"tags\":" + ToJSON
            .sequence(tags
                .stream()
                .map(ToJSON::toJSON)
                .collect(Collectors.toList()))
        + "}";
  }
}
