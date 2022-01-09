package domain.user.tests;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import domain.user.UserFactory;
import utils.Hasher;

public class UserFactoryTest {

  private final List<String> emptyTagsList = Arrays.stream(new String[] {}).collect(Collectors.toList());
  private final List<String> correctTagsList = Arrays.stream(new String[] { "tag1", "tag2", "tag3" })
      .collect(Collectors.toList());
  private final List<String> duplicatedCorrectTagsList_v1 = Arrays.stream(new String[] { "tag1", "tag2",
      "tag3", "tag3" }).collect(Collectors.toList());
  private final List<String> duplicatedCorrectTagsList_v2 = Arrays.stream(new String[] { "tag1", "tag2",
      "tag3", "tag3", "tag4", "tag4" }).collect(Collectors.toList());
  private final List<String> wrongTagsList = Arrays.stream(new String[] { "tag1", "tag2", "tag3", "tag4",
      "tag5", "tag6",
      "tag7" })
      .collect(Collectors.toList());

  @Test
  void nullUsername() {
    var euser = UserFactory.create(null, "abcde12345", correctTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "username cannot be null");
  }

  @Test
  void emptyUsername() {
    var euser = UserFactory.create("", "abcde12345", correctTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "username cannot be empty");
  }

  @Test
  void nullPassword() {
    var euser = UserFactory.create("johndoe", null, correctTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "password cannot be null");
  }

  @Test
  void emptyPassword() {
    var euser = UserFactory.create("johndoe", "", correctTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "password cannot be empty");
  }

  @Test
  void nullTagsList() {
    var euser = UserFactory.create("johndoe", "abcde12345", null);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "tags list cannot be null");
  }

  @Test
  void emptyTagsList() {
    var euser = UserFactory.create("johndoe", "abcde12345", emptyTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "tags list cannot be empty");
  }

  @Test
  void tooMuchElementsTagsLists() {
    var euser = UserFactory.create("johndoe", "abcde12345", wrongTagsList);
    assertEquals(euser.isValid(), false);
    var errors = euser.getError();
    assertEquals(errors.get(0), "tags list cannot contain more than 5 distinct elements");
  }

  @Test
  void correctTagsLists() {
    var eusers = Arrays.asList(
        UserFactory.create("johndoe", "abcde12345", correctTagsList),
        UserFactory.create("johndoe", "abcde12345", duplicatedCorrectTagsList_v1),
        UserFactory.create("johndoe", "abcde12345", duplicatedCorrectTagsList_v2));

    assertEquals(eusers.stream().map(euser -> euser.isValid()).allMatch(b -> b), true);
  }

  @Test
  void validUser() {
    var euser = UserFactory.create("johndoe", "abcde12345", duplicatedCorrectTagsList_v2);
    assertEquals(euser.isValid(), true);

    var user = euser.get();
    assertEquals(user.username, "johndoe");
    assertEquals(user.password, Hasher.hash("abcde12345"));
    assertTrue(user.tags.contains("tag1"));
    assertTrue(user.tags.contains("tag2"));
    assertTrue(user.tags.contains("tag3"));
    assertTrue(user.tags.contains("tag4"));
    assertEquals(user.tags.size(), 4);
    assertTrue(user.followers.isEmpty());
    assertTrue(user.following.isEmpty());
    assertTrue(user.posts.isEmpty());
  }

  @Test
  void validUser2() {
    var euser = UserFactory.create("johndoe", "abcde12345", duplicatedCorrectTagsList_v1);
    assertEquals(euser.isValid(), true);

    var user = euser.get();
    assertEquals(user.username, "johndoe");
    assertEquals(user.password, Hasher.hash("abcde12345"));
    assertTrue(user.tags.contains("tag1"));
    assertTrue(user.tags.contains("tag2"));
    assertTrue(user.tags.contains("tag3"));
    assertEquals(user.tags.size(), 3);
    assertTrue(user.followers.isEmpty());
    assertTrue(user.following.isEmpty());
    assertTrue(user.posts.isEmpty());
  }

}
