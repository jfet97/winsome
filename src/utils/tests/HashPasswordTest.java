package utils.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import utils.HashPassword;

public class HashPasswordTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "abcde12345",
      "password",
      "123456",
  })
  void sameHash(String password) {
    assertEquals(HashPassword.hash(password), HashPassword.hash(password));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "abcde12345",
      "password",
      "123456",
  })
  void differentHash(String password) {
    assertNotEquals(HashPassword.hash(password), HashPassword.hash(password + " "));
  }
}
