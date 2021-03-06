package utils;

import java.util.Calendar;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import domain.user.User;
import io.vavr.control.Either;

public class JWTUtils {

  private JWTUtils(String jwt) {
  }

  // create a jwt containing the username as a clain
  // using the HMAC256 algorithm and the provided secret
  public static String createJWT(String secret, String username) {
    var algorithm = Algorithm.HMAC256(secret);

    var cal = Calendar.getInstance();
    cal.setTimeInMillis(new Date().getTime());
    cal.add(Calendar.DATE, 1);

    var jwt = JWT.create()
        .withIssuer("winsome-asc")
        .withExpiresAt(cal.getTime())
        .withClaim("username", username)
        .sign(algorithm);

    return jwt;
  }

  // validate a jwt
  // using the HMAC256 algorithm and the provided secret
  public static Either<String, User> validateJWT(String secret, String jwt) {

    var toRet = Either.<String, User>right(null);
    try {
      var verifier = JWT.require(Algorithm.HMAC256(secret))
          .withIssuer("winsome-asc")
          .withClaimPresence("username")
          .build();

      var dec = verifier.verify(jwt);

      var usernameClaim = dec.getClaim("username");

      // the username is mandatory to identify the user
      if (usernameClaim.isNull()) {
        throw new RuntimeException();
      }

      toRet = Either.right(User.of(usernameClaim.asString(), "INVALD_USER", null));

    } catch (JWTVerificationException e) {
      // Invalid signature/claims e.g. token expired
      // reply accordingly
      toRet = Either.left("invalid auth token");
    } catch (Exception e) {
      // reply accordingly
      toRet = Either.left("invalid auth token");
    }

    return toRet;
  }

  // extract the username claim from a jwt
  public static Either<String, String> extractUsernameFromJWT(String jwt) {

    var toRet = Either.<String, String>right("");
    try {
      var decodedJWT = JWT.decode(jwt);

      var usernameClaim = decodedJWT.getClaim("username");

      if (usernameClaim.isNull()) {
        throw new RuntimeException("missing username claim");
      }

      toRet = Either.right(usernameClaim.asString());

    } catch (JWTVerificationException e) {
      // Invalid signature/claims e.g. token expired
      // reply accordingly
      toRet = Either.left("invalid auth token");
    } catch (Exception e) {
      // reply accordingly
      toRet = Either.left("invalid auth token: " + e.getMessage());
    }

    return toRet;
  }

  public static Either<String, String> wrapWithMessageJSON(String jwt, String message) {
    if (jwt == null || message == null) {
      return Either.left("cannot wrap jwt because of null arguments");
    } else {
      return Either.right("{\"jwt\":\"" + jwt + "\",\"message\":\"" + message + "\"}");
    }
  }

}
