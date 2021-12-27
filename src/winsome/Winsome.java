package winsome;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import domain.pojos.Post;
import domain.pojos.User;

public class Winsome {
  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();

  /**
  private void test() {
    var user = network.get("test");

    if (user != null) {

      synchronized(user.followers) {
        user.followers.add("Banana");
      }

      Post post = user.posts.get("idOfAPost");

      if (post != null) {
        synchronized (post.comments) {
          post.comments.add("banane");
        }

        synchronized (post.comments) {
          // iterate over post.comments
        }

      }
    }
  }
  */
}
