package domain.user;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import domain.post.Post;
import utils.HashPassword;

public class User {
  public String username;
  public String password;
  public List<String> tags;
  public ConcurrentMap<String, Post> posts; // <postUuid, Post>
  public Set<String> followers;
  public Set<String> following;

  public static User of(String username, String password, List<String> tags) {
    var instance = new User();

    instance.username = username; // readonly
    instance.password = HashPassword.hash(password); // readonly
    instance.tags = tags; // readonly
    instance.posts = new ConcurrentHashMap<String, Post>();
    instance.followers = new HashSet<String>(); // needs manual synchronization
    instance.following = new HashSet<String>(); // needs manual synchronization

    return instance;
  }

  public List<String> getFollowers() {
    synchronized (this.followers) {
      return this.followers.stream().collect(Collectors.toList());
    }
  }

  public List<String> getFollowing() {
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

  public <R> R computeIfIsFollowerOf(String username, Supplier<R> ifIsFollower, Supplier<R> ifIsNotFollower) {
    synchronized (this.following) {
      if (this.following.contains(username)) {
        return ifIsFollower.get();
      } else {
        return ifIsNotFollower.get();
      }
    }
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
