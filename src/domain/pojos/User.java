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

  public String toJSON() {

    var tagsLine = "\"tags\":[";
    tagsLine += this.tags
        .stream()
        .map(t -> "\"" + t.toString() + "\"")
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    tagsLine += "]";

    var postsLine = "\"posts\":{";
    postsLine += this.posts.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\":" + e.getValue().toJSON())
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    postsLine += "}";

    var followersLine = "\"followers\":[";
    synchronized (this.followers) {
      followersLine += this.followers
          .stream()
          .map(f -> "\"" + f.toString() + "\"")
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    followersLine += "]";

    var followingLine = "\"following\":[";
    synchronized (this.following) {
      followingLine += this.following
          .stream()
          .map(f -> "\"" + f.toString() + "\"")
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    followingLine += "]";

    return String.join("",
        "{",
        "\"username\":" + "\"" + this.username + "\"" + ",",
        "\"password\":" + "\"" + this.password + "\"" + ",",
        tagsLine + ",",
        postsLine + ",",
        followersLine + ",",
        followingLine,
        "}");
  }
}
