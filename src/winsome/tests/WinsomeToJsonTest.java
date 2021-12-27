package winsome.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import domain.pojos.Comment;
import domain.pojos.Post;
import domain.pojos.Reaction;
import domain.pojos.User;
import domain.pojos.wallet.Wallet;
import winsome.Winsome;

public class WinsomeToJsonTest {
  @Test
  public void fromtoJSONSingleThread()
      throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException,
      JsonProcessingException {

    var winsome = new Winsome();

    var networkField = Winsome.class.getDeclaredField("network");
    networkField.setAccessible(true);
    var network = (ConcurrentMap<String, User>) networkField.get(winsome);

    var loggedUsersField = Winsome.class.getDeclaredField("loggedUsers");
    loggedUsersField.setAccessible(true);
    var loggedUsers = (ConcurrentMap<String, Boolean>) loggedUsersField.get(winsome);

    var walletField = Winsome.class.getDeclaredField("wallet");
    walletField.setAccessible(true);
    var wallet = (Wallet) walletField.get(winsome);

    var user1 = User.of("user1", "abcde", Arrays.asList(new String[] { "tag1", "tag2" }));
    var user2 = User.of("user2", "12345", Arrays.asList(new String[] { "tag2", "tag3" }));

    network.put("user1", user1);
    network.put("user2", user2);

    wallet.addUser("user1").isRight();
    wallet.addUser("user2").isRight();

    wallet.addTransaction("user1", 2.6).isRight();
    wallet.addTransaction("user1", 4.8).isRight();
    wallet.addTransaction("user2", 9.5).isRight();

    loggedUsers.put("user1", true);
    loggedUsers.put("user2", true);

    var post1User1 = Post.of("Title 1", "Content 1", "user1");
    var post2User1 = Post.of("Title 2", "Content 2", "user1");
    var post1User2 = Post.of("Title 1", "Content 1", "user2");

    user1.posts.put(post1User1.uuid, post1User1);
    user1.posts.put(post2User1.uuid, post2User1);
    user2.posts.put(post1User2.uuid, post1User2);

    post1User1.reactions.add(Reaction.of(true, post1User1.uuid, user2.username));
    post2User1.reactions.add(Reaction.of(false, post2User1.uuid, user2.username));
    post1User2.comments.add(Comment.of("A comment", post1User2.uuid, user1.username));
    post1User1.comments.add(Comment.of("Another comment", post1User1.uuid, user1.username));

    user2.following.add(user1.username);
    user1.followers.add(user2.username);

    // serialization
    System.out.println(winsome.toJSON());

    var objectMapper = new ObjectMapper();
    objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    assertDoesNotThrow(() -> objectMapper.readTree(winsome.toJSON()));

    // parsing
    var winsomeFromJSON = objectMapper.readValue(winsome.toJSON(), Winsome.class);
    String indented = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(winsomeFromJSON);
    System.out.println(indented);

    var networkFieldFromJSON = Winsome.class.getDeclaredField("network");
    networkFieldFromJSON.setAccessible(true);
    var networkFromJSON = (ConcurrentMap<String, User>) networkFieldFromJSON.get(winsomeFromJSON);

    var loggedUsersFieldFromJSON = Winsome.class.getDeclaredField("loggedUsers");
    loggedUsersFieldFromJSON.setAccessible(true);
    var loggedUsersFromJSON = (ConcurrentMap<String, Boolean>) loggedUsersFieldFromJSON.get(winsomeFromJSON);

    // does jackson deserialization make its work?
    assertTrue(networkFromJSON instanceof ConcurrentMap<?, ?>);
    assertTrue(loggedUsersFromJSON instanceof ConcurrentMap<?, ?>);
    assertTrue(networkFromJSON.entrySet()
        .stream()
        .allMatch(e -> e.getValue().tags instanceof List<?> &&
            e.getValue().followers instanceof List<?> &&
            e.getValue().following instanceof List<?> &&
            e.getValue().posts instanceof ConcurrentMap<?, ?>));
  }
}
