package domain.reaction;

import io.vavr.collection.Seq;
import io.vavr.control.Validation;

public class ReactionFactory {

  private ReactionFactory() {
  }

  private static interface ReactionValidator {
    public Validation<Seq<String>, Reaction> validateReaction(Boolean isUpvote, String postUuid, String username);
  }

  private static ReactionValidator validator = new ReactionValidator() {
    @Override
    // validate a post
    public Validation<Seq<String>, Reaction> validateReaction(Boolean isUpvote, String postUuid, String username) {
      return Validation.combine(validateIsUpvote(isUpvote), validatePostUuid(postUuid), validateUsername(username))
          .ap(Reaction::of);
    }

    // validate isUpvote fiels
    private Validation<String, Boolean> validateIsUpvote(Boolean isUpvote) {
      var errorMessage = "";

      if (isUpvote == null)
        errorMessage = "isUpvote cannot be null";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(isUpvote);
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

    // validate the username
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

  // try to create a Reaction instance, collect each error if any
  public static Validation<Seq<String>, Reaction> create(Boolean isUpvote, String postUuid, String username) {
    return validator.validateReaction(isUpvote, postUuid, username);
  }
}
