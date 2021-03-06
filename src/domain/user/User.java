package domain.user;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import domain.post.Post;
import utils.Hasher;

public class User {
  public String username;
  public String password;
  public List<String> tags;
  public ConcurrentMap<String, Post> posts; // Map<postUuid, Post>
  public Set<String> followers;
  public Set<String> following;

  public static User of(String username, String password, List<String> tags, Boolean hashPassword) {
    var instance = new User();

    instance.username = username; // readonly
    instance.password = hashPassword ? Hasher.hash(password) : password; // readonly
    instance.tags = tags; // readonly
    instance.posts = new ConcurrentHashMap<String, Post>();
    instance.followers = new HashSet<String>(); // needs manual synchronization
    instance.following = new HashSet<String>(); // needs manual synchronization

    return instance;
  }

  public static User of(String username, String password, List<String> tags) {
    return of(username, password, tags, true);
  }

  public List<String> getFollowers() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (this.followers) {
      return this.followers.stream().collect(Collectors.toList());
    }
  }

  public List<String> getFollowing() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (this.following) {
      return this.following.stream().collect(Collectors.toList());
    }
  }

  public Boolean addFollower(String follower) {
    synchronized (this.followers) {
      return this.followers.add(follower);
    }
  }

  public Boolean addFollowing(String following) {
    synchronized (this.following) {
      return this.following.add(following);
    }
  }

  public Boolean removeFollower(String follower) {
    synchronized (this.followers) {
      return this.followers.remove(follower);
    }
  }

  public Boolean removeFollowing(String following) {
    synchronized (this.following) {
      return this.following.remove(following);
    }
  }

  // lock the followers and then call the callback with a clone
  // of the followers set
  public void synchronizedActionOnFollowers(Consumer<List<String>> cb) {
    synchronized (this.followers) {
      cb.accept(this.followers.stream().collect(Collectors.toList()));
    }
  }

  public String toJSON() {
    return this.toJSON(true);
  }

  public String toJSON(Boolean includePassword) {

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
        includePassword ? "\"password\":" + "\"" + this.password + "\"" + "," : "",
        tagsLine + ",",
        postsLine + ",",
        followersLine + ",",
        followingLine,
        "}");
  }
}
