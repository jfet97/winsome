package domain.user;

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
      var usernameTrimmed = username != null ? username.trim() : null;

      if (usernameTrimmed == null)
        errorMessage = "username cannot be null";
      else if (usernameTrimmed.equals(""))
        errorMessage = "username cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(usernameTrimmed);
    }

    private Validation<String, String> validatePassword(String password) {
      var errorMessage = "";
      var passwordTrimmed = password != null ? password.trim() : null;

      if (passwordTrimmed == null)
        errorMessage = "password cannot be null";
      else if (passwordTrimmed.equals(""))
        errorMessage = "password cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(passwordTrimmed);
    }

    private Validation<String, List<String>> validateTags(List<String> tags) {
      var errorMessage = "";

      if (tags == null)
        errorMessage = "tags list cannot be null";
      else if (tags.isEmpty())
        errorMessage = "tags list cannot be empty";

      var uniqueTags = tags;
      if (errorMessage.equals("")) {
        uniqueTags = tags.stream()
            .distinct()
            .filter(tag -> tag != null && !tag.equals(""))
            .map(String::trim)
            .map(t -> t.replaceAll("\n|\r|\r\n", ""))
            .collect(Collectors.toList());

        if (uniqueTags.size() > 5)
          errorMessage = "tags list cannot contain more than 5 distinct elements";
      }

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(uniqueTags);

    }
  };

  public static Validation<Seq<String>, User> create(String username, String password, List<String> tags) {
    return validator.validateUser(username, password, tags);
  }

}
