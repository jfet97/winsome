package domain.pojos;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import utils.HashPassword;

public class User {
  public String username;
  public String password;
  public List<String> tags;
  public ConcurrentMap<String, Post> posts; // <postUuid, Post>
  public List<String> followers;
  public List<String> following;

  public static User of(String username, String password, List<String> tags) {
    var instance = new User();

    instance.username = username; // readonly
    instance.password = HashPassword.hash(password); // readonly
    instance.tags = tags; // readonly
    instance.posts = new ConcurrentHashMap<String, Post>();
    instance.followers = new LinkedList<String>(); // needs manual synchronization
    instance.following = new LinkedList<String>(); // needs manual synchronization

    return instance;
  }

  @Override
  public String toString() {

    var tagsLine = "\"tags\": [\n";
    tagsLine += this.tags
        .stream()
        .map(c -> c.toString())
        .reduce("", (acc, curr) -> acc + ",\n" + curr);
    tagsLine += "\n]";

    var postsLine = "\"posts\": {\n";
    postsLine += this.posts.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
        .reduce("", (acc, curr) -> acc + ",\n" + curr);
    postsLine += "\n}";

    var followersLine = "\"followers\": [\n";
    synchronized (this.followers) {
      followersLine += this.followers
          .stream()
          .map(c -> c.toString())
          .reduce("", (acc, curr) -> acc + ",\n" + curr);
    }
    followersLine += "\n]";

    var followingLine = "\"following\": ";
    synchronized (this.following) {
      followingLine += this.following
          .stream()
          .map(c -> c.toString())
          .reduce("", (acc, curr) -> acc + ",\n" + curr);
    }
    followingLine += "\n]";

    return String.join("\n",
        "{",
        "  \"username\": " + "\"" + this.username + "\"" + ",",
        "  " + tagsLine + ",",
        "  " + postsLine + ",",
        "  " + followersLine + ",",
        "  " + followingLine,
        "}");
  }
}
