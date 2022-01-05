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
    // validate a comment
    public Validation<Seq<String>, Comment> validateComment(String text, String postUuid, String author) {
      return Validation.combine(validateText(text), validatePostUuid(postUuid), validateAuthor(author)).ap(Comment::of);
    }

    // validate the text
    private Validation<String, String> validateText(String text) {
      var errorMessage = "";
      var textTrimmed = text != null ? text.trim().replaceAll("\n|\r|\r\n", "") : null;

      if (textTrimmed == null)
        errorMessage = "text cannot be null";
      else if (textTrimmed.equals(""))
        errorMessage = "text cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(textTrimmed);
    }

    // validate the reference to the post
    private Validation<String, String> validatePostUuid(String postUuid) {
      var errorMessage = "";
      var postUuidTrimmed = postUuid != null ? postUuid.trim() : null;

      if (postUuidTrimmed == null)
        errorMessage = "postUuid cannot be null";
      else if (postUuidTrimmed.equals(""))
        errorMessage = "postUuid cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(postUuidTrimmed);
    }

    // validate the author
    private Validation<String, String> validateAuthor(String author) {
      var errorMessage = "";
      var authorTrimmed = author != null ? author.trim() : null;

      if (authorTrimmed == null)
        errorMessage = "author cannot be null";
      else if (authorTrimmed.equals(""))
        errorMessage = "author cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(authorTrimmed);
    }

  };

  // try to create a Comment instance, collect each error if any
  public static Validation<Seq<String>, Comment> create(String text, String postUuid, String author) {
    return validator.validateComment(text, postUuid, author);
  }
}
