package domain.factories;

import domain.pojos.Comment;
import io.vavr.collection.Seq;
import io.vavr.control.Validation;

public class CommentFactory {

  private CommentFactory() {
  }

  private static interface CommentValidator {
    public Validation<Seq<String>, Comment> validateComment(String text, String postUuid, String username);
  }

  private static CommentValidator validator = new CommentValidator() {
    @Override
    public Validation<Seq<String>, Comment> validateComment(String text, String postUuid, String username) {
      return Validation.combine(validateText(text), validatePostUuid(postUuid), validateUsername(username)).ap(Comment::of);
    }

    private Validation<String, String> validateText(String text) {
      var errorMessage = "";
      var textTrimmed = text != null ? text.trim() : null;

      if (textTrimmed == null)
        errorMessage = "text cannot be null";
      else if (textTrimmed.equals(""))
        errorMessage = "text cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(textTrimmed);
    }

    private Validation<String, String> validatePostUuid(String postUuid) {
      var errorMessage = "";
      var postUuidTrimmed = postUuid != null ? postUuid.trim() : null;

      if (postUuidTrimmed == null)
        errorMessage = "postUuid cannot be null";
      else if (postUuidTrimmed.equals(""))
        errorMessage = "postUuid cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(postUuidTrimmed);
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

  };

  public static Validation<Seq<String>, Comment> create(String text, String postUuid, String username) {
    return validator.validateComment(text, postUuid, username);
  }
}
