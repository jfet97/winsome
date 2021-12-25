package domain.pojos;

import java.util.LinkedList;
import java.util.List;

import utils.HashPassword;

public class User {
  public String username;
  public String password;
  public List<String> tags;
  public List<Post> posts;
  public List<String> followers;
  public List<String> following;

  public static User of(String username, String password, List<String> tags) {
    var newUser = new User();

    newUser.username = username;
    newUser.password = HashPassword.hash(password);
    newUser.tags = tags;
    newUser.posts = new LinkedList<Post>();
    newUser.followers = new LinkedList<String>();
    newUser.following = new LinkedList<String>();

    return newUser;
  }
}
