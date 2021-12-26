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
    var newUser = new User();

    newUser.username = username;
    newUser.password = HashPassword.hash(password);
    newUser.tags = tags; // needs manual synchronization
    newUser.posts = new ConcurrentHashMap<String, Post>();
    newUser.followers = new LinkedList<String>(); // needs manual synchronization
    newUser.following = new LinkedList<String>(); // needs manual synchronization

    return newUser;
  }
}
