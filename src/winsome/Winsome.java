package winsome;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import domain.factories.UserFactory;
import domain.pojos.Post;
import domain.pojos.User;
import io.vavr.control.Either;
import secrets.Secrets;
import utils.HashPassword;

public class Winsome {

  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> loggedUsers = new ConcurrentHashMap<>();

  /**
   * private void test() {
   * var user = network.get("test");
   * 
   * if (user != null) {
   * 
   * synchronized(user.followers) {
   * user.followers.add("Banana");
   * }
   * 
   * Post post = user.posts.get("idOfAPost");
   * 
   * if (post != null) {
   * synchronized (post.comments) {
   * post.comments.add("banane");
   * }
   * 
   * synchronized (post.comments) {
   * // iterate over post.comments
   * }
   * 
   * }
   * }
   * }
   */

  public Either<String, User> register(String username, String password, List<String> tags) {

    // we have to avoid data race condition:
    // two or more threads that try to put the same new user

    var euser = UserFactory
        .create(username, password, tags)
        .toEither()
        .mapLeft(seq -> seq.mkString("\n"));

    return euser
        .map(u -> network.putIfAbsent(username, u))
        .flatMap(u -> {
          // putIfAbsent returns null if there was no previous mapping for the key
          // => success
          if (u == null) {
            return euser;
          } else {
            return Either.left("user already exists");
          }
        });
  }

  public Either<String, String> login(String username, String password) {

    return Either.<String, User>right(network.get(username))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !HashPassword.hash(password).equals(u.password) ? Either.left("invalid password")
            : Either.right(u))
        .flatMap(u -> {
          // putIfAbsent returns null if there was no previous mapping for the key
          if (loggedUsers.putIfAbsent(u.username, true) == null) {

            var algorithm = Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET);

            var cal = Calendar.getInstance();
            cal.setTimeInMillis(new Date().getTime());
            cal.add(Calendar.DATE, 1);

            var jwt = JWT.create()
                .withExpiresAt(cal.getTime())
                .withClaim("username", u.username)
                .withClaim("tags", u.tags)
                .sign(algorithm);

            return Either.right(jwt);
          } else {
            return Either.left("user already logged");
          }
        });
  }

  @Override
  public String toString() {

    var networkLine = "\"network\": {\n";
    networkLine += this.network.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
        .reduce("", (acc, curr) -> acc + ",\n" + curr);
    networkLine += "\n}";

    var loggedUsersLine = "\"loggedUsers\": {\n";
    loggedUsersLine += this.loggedUsers.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
        .reduce("", (acc, curr) -> acc + ",\n" + curr);
    loggedUsersLine += "\n}";

    return String.join("\n",
        "{",
        "  " + networkLine + ",",
        "  " + loggedUsersLine,
        "}");
  }

}
