package domain.comment;

import io.vavr.collection.Seq;
import io.vavr.control.Validation;

public class CommentFactory {

  private CommentFactory() {
  }

  private static interface CommentValidator {
    public Validation<Seq<String>, Comment> validateComment(String text, String postUuid, String author);
  }

  private static CommentValidator validator = new CommentValidator() {
    @Override
    public Validation<Seq<String>, Comment> validateComment(String text, String postUuid, String author) {
      return Validation.combine(validateText(text), validatePostUuid(postUuid), validateUsername(author)).ap(Comment::of);
    }

    private Validation<String, String> validateText(String text) {
      var errorMessage = "";
      var textTrimmed = text != null ? text.trim().replaceAll("\n|\r|\r\n", "") : null;

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

    private Validation<String, String> validateUsername(String author) {
      var errorMessage = "";
      var authorTrimmed = author != null ? author.trim() : null;

      if (authorTrimmed == null)
        errorMessage = "author cannot be null";
      else if (authorTrimmed.equals(""))
        errorMessage = "author cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(authorTrimmed);
    }

  };

  public static Validation<Seq<String>, Comment> create(String text, String postUuid, String author) {
    return validator.validateComment(text, postUuid, author);
  }
}
