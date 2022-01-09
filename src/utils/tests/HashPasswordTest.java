package utils.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import utils.Hasher;

public class HashPasswordTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "abcde12345",
      "password",
      "123456",
  })
  void sameHash(String password) {
    assertEquals(Hasher.hash(password), Hasher.hash(password));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "abcde12345",
      "password",
      "123456",
  })
  void differentHash(String password) {
    assertNotEquals(Hasher.hash(password), Hasher.hash(password + " "));
  }
}
