package domain.factories.tests;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import domain.factories.PostFactory;

public class PostFactoryTest {

  @Test
  void nullTitle() {
    var epost = PostFactory.create(null, "Random content", "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "title cannot be null");
  }

  @Test
  void maxLengthExceededTitle() {
    var epost = PostFactory.create(new String(new char[21]).replace('\0', 'a'), "Random content", "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "title max length is 20 characters");
  }

  @Test
  void emptyTitle() {
    var epost = PostFactory.create("", "Random content", "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "title cannot be empty");
  }

  @Test
  void nullContent() {
    var epost = PostFactory.create("A title", null, "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "content cannot be null");
  }

  @Test
  void maxLengthExceededContent() {
    var epost = PostFactory.create("A title", new String(new char[501]).replace('\0', 'a'), "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "content max length is 500 characters");
  }

  @Test
  void emptyContent() {
    var epost = PostFactory.create("A title", "", "John Doe");
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "content cannot be empty");
  }

  @Test
  void nullAuthor() {
    var epost = PostFactory.create("A title", "Random content", null);
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.get(0), __ -> ""), "author cannot be null");
  }

  @Test
  void threeErrors() {
    var epost = PostFactory.create(null, null, null);
    assertEquals(epost.isValid(), false);
    assertEquals(epost.fold(set -> set.foldLeft(0, (tot, curr) -> tot + 1), post -> 0), 3);

    var errors = epost.getError();
    assertTrue(errors.asJava().contains("title cannot be null"));
    assertTrue(errors.asJava().contains("content cannot be null"));
    assertTrue(errors.asJava().contains("author cannot be null"));
  }

  @Test
  void validPost() {
    var epost = PostFactory.create("A title", "Random content", "John Doe");
    assertEquals(epost.isValid(), true);

    var post = epost.fold(set -> null, x -> x);
    assertEquals(post.author, "John Doe");
    assertEquals(post.content, "Random content");
    assertEquals(post.title, "A title");
    assertTrue(post.comments.isEmpty());

  }
}
