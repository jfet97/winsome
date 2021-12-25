package domain.factories;

import domain.pojos.User;
import io.vavr.collection.Seq;
import io.vavr.control.Validation;

import java.util.List;
import java.util.stream.Collectors;

public class UserFactory {
  private UserFactory() {
  }

  private static interface UserValidator {
    public Validation<Seq<String>, User> validateUser(String username, String password, List<String> tags);
  }

  private static UserValidator validator = new UserValidator() {
    @Override
    public Validation<Seq<String>, User> validateUser(String username, String password, List<String> tags) {
      return Validation.combine(validateUsername(username), validatePassword(password), validateTags(tags))
          .ap(User::of);
    }

    private Validation<String, String> validateUsername(String username) {
      var errorMessage = "";

      if (username == null)
        errorMessage = "username cannot be null";
      else if (username == "")
        errorMessage = "username cannot be empty";

      return errorMessage != "" ? Validation.invalid(errorMessage) : Validation.valid(username);
    }

    private Validation<String, String> validatePassword(String password) {
      var errorMessage = "";

      if (password == null)
        errorMessage = "password cannot be null";
      else if (password == "")
        errorMessage = "password cannot be empty";

      return errorMessage != "" ? Validation.invalid(errorMessage) : Validation.valid(password);
    }

    private Validation<String, List<String>> validateTags(List<String> tags) {
      var errorMessage = "";

      if (tags == null)
        errorMessage = "tags list cannot be null";
      else if (tags.isEmpty())
        errorMessage = "tags list cannot be empty";

      var uniqueTags = tags;
      if (errorMessage == "") {
        uniqueTags = tags.stream().distinct().collect(Collectors.toList());

        if (uniqueTags.size() > 5)
          errorMessage = "tags list cannot contain more than 5 distinct elements";
      }

      return errorMessage != "" ? Validation.invalid(errorMessage) : Validation.valid(uniqueTags);

    }

  };

  public static Validation<Seq<String>, User> create(String username, String password, List<String> tags) {
    return validator.validateUser(username, password, tags);
  }

}
