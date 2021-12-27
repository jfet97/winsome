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
  public ConcurrentMap<String, Post> posts;
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
}
